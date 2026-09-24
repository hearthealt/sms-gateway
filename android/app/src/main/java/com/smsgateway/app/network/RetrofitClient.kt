package com.smsgateway.app.network

import android.content.Context
import com.google.gson.Gson
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.util.AuthState
import com.smsgateway.app.util.DevicePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 全部可变字段都是 @Volatile，全部写入路径都是 @Synchronized。
 *
 * 这不是防御性写法，是这两条并发的必然要求：
 * - 改服务器地址走设置页/扫码页（主线程），而 `getApiService()` 走心跳与上报（IO 线程）。
 *   两者撞上时，若 [configure] 只在主线程改 [baseUrl]、而 IO 线程正好在判空与构建之间，
 *   就可能把用**旧地址**构建好的客户端写回 [apiService] —— 此后所有请求都发往旧服务器，
 *   而界面显示的是新地址，怎么核都对不上。
 * - [apiService]、[retrofit]、[httpClient] 三者的写入与读取原本不在同一个锁里。
 *
 * @Volatile 管的是「读到的不是某个中间态」；@Synchronized 管的是「构建与写入是一件事」。
 * 两者缺一，问题都只是从「每次必现」变成「偶发」，而偶发的那一类最难查。
 */
object RetrofitClient {

    @Volatile
    private var baseUrl: String = "http://10.0.2.2:8080/"

    @Volatile
    private var deviceToken: String? = null

    @Volatile
    private var deviceId: String? = null

    @Volatile
    private var apiService: ApiService? = null

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var probeClient: OkHttpClient? = null

    /**
     * Application context，供鉴权拦截器在收到 401 时清理本地令牌。
     * 只存 Application，不会泄漏 Activity。
     */
    @Volatile
    private var appContext: Context? = null

    /** 只用于解析探活响应。Retrofit 那个 Gson 实例藏在 GsonConverterFactory 里取不出来。 */
    private val gson = Gson()

    /** Retrofit 要求 baseUrl 以 / 结尾，这里统一收口，免得configure 与 ensureConfigured 各写一份。 */
    private fun normalizedUrl(url: String): String = url.trimEnd('/') + "/"

    @Synchronized
    fun configure(url: String, token: String?, id: String?) {
        baseUrl = normalizedUrl(url)
        deviceToken = token
        deviceId = id
        resetClient()
    }

    /**
     * 按 DevicePrefs 里的已保存配置幂等地装配客户端。
     *
     * WorkManager 拉起进程跑上传任务、或广播接收器被唤起时，DashboardViewModel 根本没被创建过。
     * 若不在这些入口兜底，请求会带着字段初始值（模拟器回环地址 10.0.2.2）且不带 Authorization 头
     * 打出去 —— 只会 401 然后无限重试，短信永远传不上去。
     *
     * 只在地址或凭据真的变了时才重建，否则每 30 秒一次的心跳都会新建一个 OkHttpClient。
     *
     * 整个方法在锁里：「比对四个字段 → 决定要不要重建」必须是一次原子判断。
     * 拆开的话，两个线程可以同时读到「不一样」然后各自 configure 一次。
     */
    @Synchronized
    fun ensureConfigured(context: Context) {
        appContext = context.applicationContext

        val url = DevicePrefs.serverUrl(context)
        val token = DevicePrefs.deviceToken(context).ifBlank { null }
        val id = DevicePrefs.deviceId(context).ifBlank { null }

        if (apiService != null &&
            baseUrl == normalizedUrl(url) &&
            deviceToken == token &&
            deviceId == id
        ) {
            return
        }

        configure(url, token, id)
    }

    @Synchronized
    fun updateToken(token: String?) {
        deviceToken = token
        resetClient()
    }

    @Synchronized
    fun updateDeviceId(id: String?) {
        deviceId = id
        resetClient()
    }

    fun getDeviceToken(): String? = deviceToken
    fun getDeviceId(): String? = deviceId
    fun getBaseUrl(): String = baseUrl

    @Synchronized
    private fun resetClient() {
        apiService = null
        retrofit = null
        httpClient = null
    }

    /**
     * 地址能否用作 baseUrl。设置页保存前用它挡一道。
     *
     * 用 OkHttp 自己的解析器判定，而不是自己写规则：Retrofit 的 baseUrl 底层就是 HttpUrl，
     * 凡是这里判为合法的，后续构造时就一定不会抛 IllegalArgumentException。自己写正则
     * 反而容易漏掉端口越界（:80801）这类情况 —— 而那正是会闪退的那种。
     */
    fun isValidBaseUrl(url: String): Boolean = normalizedUrl(url).toHttpUrlOrNull() != null

    private fun buildHttpClient(): OkHttpClient {
        // 只在调试包打正文。BODY 级别会把请求头与响应体整个打进 logcat，而请求头里带着
        // `Authorization: Bearer <deviceToken>`，正文里是短信全文与提取出的验证码 ——
        // 发布版照打，等于把设备令牌（可冒充该设备上报与读取短信）和用户验证码
        // 明文留在任何能读日志的地方（adb logcat、bug report、带日志权限的工具）。
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    deviceTokenProvider = { deviceToken },
                    deviceIdProvider = { deviceId },
                    // 用 Application context，不存在泄漏。它由 ensureConfigured 记下来 ——
                    // 心跳和上报在进程被 WorkManager 拉起时都会调它，所以不会拿不到。
                    //
                    // 把被拒的那份令牌一路传下去：AuthState 要拿它比对当前令牌，
                    // 免得一条迟到的 401 删掉刚重新注册拿到的新令牌（见 markTokenRejected）。
                    onUnauthorized = { rejected ->
                        appContext?.let { AuthState.markTokenRejected(it, rejected) }
                    }
                )
            )
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 懒构建 + 缓存，整个判断与写入在同一把锁里。
     *
     * 若只是 `httpClient ?: build().also { httpClient = it }`，两个线程可以各建一个客户端，
     * 后写的那个赢 —— 而**先构建的那个可能正被别的调用方拿着用**，于是同一进程里同时
     * 存在两套连接池与两个 AuthInterceptor 实例。锁与方法重入（它被 getApiService 调用，
     * 而后者也持锁）在这里都是安全的：同一线程可以重复进入同一把监视器锁。
     */
    @Synchronized
    private fun getHttpClient(): OkHttpClient =
        httpClient ?: buildHttpClient().also { httpClient = it }

    /**
     * 探活专用客户端。它**独立构建**，不是从共享客户端 `newBuilder()` 派生的 ——
     * 这一点是关键，不是风格选择。
     *
     * 探活的目标地址是用户在设置页里随手敲的。在「对面是不是本服务」被确认之前，
     * 请求**已经发出去了**，所以不能挂 AuthInterceptor：否则设备令牌
     * （`Authorization: Bearer …` 与 `X-Device-Id`）会被发到那个地址去。
     * 现场人员把地址敲成同事的开发机、打印机 Web 页、路由器管理页都会中招，
     * 而明文 HTTP 让同网段任何抓包方都能直接看到。探活打的是免鉴权的 /api/health，
     * 本来也不需要令牌。
     *
     * 另外顺带收紧超时：共享客户端的 30 秒是留给心跳和上报的余量，对「测试连接」
     * 这个按钮太长 —— 端口关着一般立刻回 RST，但**被防火墙丢包**的连接要干等
     * 半分钟才出结果，用户会以为按钮卡死了。
     *
     * 不随 resetClient() 重建：它不依赖地址与令牌，没有任何可失效的配置。
     */
    @Synchronized
    private fun getProbeClient(): OkHttpClient =
        probeClient ?: buildProbeClient().also { probeClient = it }

    private fun buildProbeClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // callTimeout 兜住整个调用（含重试），比单看各阶段超时更可靠
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    /**
     * 取（必要时构建）API 客户端。
     *
     * 整个方法加锁：它此前是「判空 → 构建 → 取强制非空」，而 apiService 是普通 var。
     * 主线程在注册成功路径上调 updateToken() → resetClient() 把它置空的瞬间，
     * IO 线程的心跳若正好走到判空与 `!!` 之间，就会抛 NPE。
     * 窗口很窄，但这是每 30 秒都会跑一次的路径，长期运行下不值得赌。
     * 构建只会发生一次，加锁的代价可以忽略。
     */
    @Synchronized
    fun getApiService(): ApiService {
        val existing = apiService
        if (existing != null) return existing

        val built = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return built.create(ApiService::class.java).also {
            retrofit = built
            apiService = it
        }
    }

    /**
     * 探活：打服务端的 /api/health，确认**对面确实是本服务**。
     *
     * 原先打的是根路径 /，只要拿到 HTTP 响应就算「已连通」—— 但根路径谁都不会映射，
     * 返回 404 照样算通，于是把地址填成路由器管理页或别的占着 8080 的服务，界面
     * 一样报成功。改成打 health 并校验它回的服务标识，填错地址才会明确报错。
     *
     * 接受 url 参数而不是直接读 baseUrl，这样设置页可以测输入框里当前的值，
     * 不必先保存再测。本函数不抛异常（协程取消除外），失败也以 Unreachable 返回，
     * 因为「连不上」和「连上了但不是本服务」对调用方是同一层级的正常结果。
     *
     * @param onAttempt 第 N 次尝试开始时回调（N 从 1 开始）。界面拿它显示「正在重试」，
     *   免得最长十几秒的等待看起来像死机。
     */
    suspend fun probe(
        url: String,
        onAttempt: ((Int) -> Unit)? = null
    ): ProbeResult = withContext(Dispatchers.IO) {
        // 失败重试，隔 1.5 秒、之后翻倍。
        //
        // 只对 Unreachable 重试：NotOurService（有响应但不是本服务）是确定答案，
        // 地址就是错的，再试一次只是白等。
        //
        // 重试预算分两档，取决于 WiFi 是不是刚连上（见 [wifiJustCameUp]）：
        //
        //   - **刚连上**：链路要十几秒才真正可用，失败可能是秒回的「本机没路由」，
        //     也可能是干等超时 —— 两种都得一直试到预算用完，否则现场就是
        //     「连不上，过一会再点一次就好了」，全看运气。
        //   - **不在窗口里**（WiFi 早就连上、或者压根没连 WiFi）：保持原行为。
        //     慢失败说明包发出去了对面没回（防火墙丢包、服务没起），再试两次只是白等。
        val settling = wifiJustCameUp()
        val maxAttempts = if (settling) PROBE_SETTLING_ATTEMPTS else PROBE_ATTEMPTS
        // 用可空而不是 Long.MAX_VALUE：`now + Long.MAX_VALUE` 会溢出成负数，
        // 于是「不是刚切网」时下面那句 deadline 判断立刻成立 —— 变成一次都不重试，
        // 把原来那条「秒回失败就重试」的规则一起废掉了。
        val deadline = if (settling) System.currentTimeMillis() + PROBE_SETTLING_BUDGET_MS else null

        var delayMs = PROBE_RETRY_DELAY_MS
        var lastFailure: ProbeResult.Unreachable? = null

        repeat(maxAttempts) { attempt ->
            if (attempt > 0) onAttempt?.invoke(attempt + 1)

            val startedAt = System.currentTimeMillis()
            val result = probeOnce(url)
            val elapsed = System.currentTimeMillis() - startedAt

            if (result !is ProbeResult.Unreachable) return@withContext result
            lastFailure = result

            if (attempt >= maxAttempts - 1) return@withContext fail(result, settling)
            // 稳了之后还慢失败 → 对面没响应，别再耗
            if (elapsed > PROBE_FAST_FAILURE_MS && !settling) return@withContext fail(result, settling)
            // 刚切网也不能无限试：窗口再长也有个头
            if (deadline != null && System.currentTimeMillis() > deadline) {
                return@withContext fail(result, settling)
            }

            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(PROBE_RETRY_MAX_DELAY_MS)
        }
        fail(lastFailure!!, settling)
    }

    /**
     * 放弃时收尾。
     *
     * 「刚切网」那十几秒里试遍了还不通，原因几乎不可能是地址写错 —— 是链路还没就绪。
     * 那就把下一步动作一起说出来：等十几秒再点一次。否则现场看到「没路由」只会去查地址、
     * 查防火墙，全查完了再点一次发现好了。
     */
    private fun fail(result: ProbeResult.Unreachable, settling: Boolean): ProbeResult.Unreachable =
        if (!settling) {
            result
        } else {
            result.copy(reason = result.reason + "。刚连上 WiFi 时局域网一般要十几秒才通，稍等再试一次")
        }

    /**
     * WiFi 是不是刚连上（[WIFI_SETTLING_MS] 以内）。
     *
     * 实测 WiFi 拿到 IP 之后**还要十几秒局域网才通**，而现场扫码恰好就在那十几秒里。
     * 跟踪不起来时（回调没注册上）或当前没有 WiFi 时按「不在窗口里」处理：
     * 也就是保持原来的快节奏，不去赌 —— 关掉 WiFi 只剩蜂窝时，
     * 探测不该因此从等 8 秒变成等 20 秒。
     */
    private fun wifiJustCameUp(): Boolean =
        NetworkWatch.millisSinceWifiAvailable()?.let { it < WIFI_SETTLING_MS } ?: false

    private suspend fun probeOnce(url: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            // 构造 Request 必须在 try 之内。url() 会解析地址，用户在设置页里敲进一个
            // 非法端口（如 :80801）时它抛 IllegalArgumentException —— 放到 try 外面，
            // 这个异常会直接掀掉调用方的协程、闪退，而不是变成一条「连不上」的提示。
            val request = Request.Builder().url(normalizedUrl(url) + HEALTH_PATH).get().build()

            getProbeClient().newCall(request).execute().use { response ->
                val body = response.body?.string()
                val envelope = body?.let {
                    runCatching { gson.fromJson(it, HealthEnvelope::class.java) }.getOrNull()
                }

                if (response.isSuccessful && envelope?.data?.service == SERVICE_NAME) {
                    // 服务标识对上了。数据库那项单独回报：服务器活着但库挂了时，
                    // 注册和上报全会失败，不该显示成一切正常。
                    ProbeResult.Online(databaseUp = envelope.data.database != "DOWN")
                } else {
                    ProbeResult.NotOurService(response.code, body?.take(SNIPPET_LENGTH))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IllegalArgumentException（地址非法）也走这里：对现场人员来说，
            // 「端口敲错了」和「连不上」是同一件事，都该是一条提示而不是一次崩溃。
            ProbeResult.Unreachable(describeConnectFailure(e))
        }
    }

    /**
     * 把连接失败翻成现场能据以行动的短句。
     *
     * 直接把异常消息抛出去只会得到 OkHttp 的
     * `Failed to connect to /192.168.253.30:8080` —— 那句话没有任何信息量：
     * 真正的原因藏在它的 cause 里，而三种原因的处置方式完全不同：
     * 超时是防火墙，不可达是走错了网络，拒绝是端口上没服务。
     */
    private fun describeConnectFailure(e: Throwable): String {
        // OkHttp 会把原因包一层（RouteException / ConnectException 套内层），取最里层那个
        val root = generateSequence(e) { it.cause }.last()
        val raw = root.message.orEmpty()

        return when {
            root is SocketTimeoutException || raw.contains("timed out", ignoreCase = true) ->
                "连接超时：包发出去了但对面没回，检查服务端防火墙是否放行该端口"

            // "unreach" 一次盖住两种：Android 对「网络不可达」抛的是
            // `isConnected failed: ENETUNREACH`，对「主机不可达」抛的是
            // `isConnected failed: EHOSTUNREACH (No route to host)` ——
            // 后者不含 "unreachable" 这个词，只匹配它的话会漏掉最常见的那种。
            raw.contains("unreach", ignoreCase = true) ||
                raw.contains("no route to host", ignoreCase = true) ||
                root is NoRouteToHostException ->
                "网络不可达：本机到该地址没有路由。WiFi 刚重连、或同时开着蜂窝数据时会这样"

            // 本机这一侧把连接断掉了。真机上「刚连上 WiFi 那十几秒」量到过两种：
            // `isConnected failed: ECONNABORTED (Software caused connection abort)`、
            // 以及 `Socket closed`（OkHttp 的 callTimeout 到点时会把 socket 关掉，
            // 表面上报的是这个）。两种都落在本机，换个时机就好，不是对方的问题。
            raw.contains("ECONNABORTED", ignoreCase = true) ||
                raw.contains("Software caused connection abort", ignoreCase = true) ||
                raw.contains("Socket closed", ignoreCase = true) ->
                "连接被本机中断：网络刚切换（WiFi 刚连上或刚断开）时常见，稍等重试"

            raw.contains("refused", ignoreCase = true) ->
                "连接被拒绝：对面在，但那个端口上没有服务在监听"

            root is UnknownHostException -> "地址解析失败：${root.message.orEmpty()}"

            else -> raw.ifBlank { root.javaClass.simpleName }
        }
    }
}

/**
 * 「测试连接」的结果。
 *
 * 分三态而不沿用原来的「HTTP 状态码」：那样把「对面是本服务」和「对面只是个随便的
 * HTTP 服务」混为一谈，地址填错也报成功。
 */
sealed interface ProbeResult {

    /** 确认对面是本服务。databaseUp 表示它后端的数据库是否可用。 */
    data class Online(val databaseUp: Boolean) : ProbeResult

    /** 地址能通，但对面不是本服务 —— 多半是地址或端口填错了。 */
    data class NotOurService(val httpCode: Int, val bodySnippet: String?) : ProbeResult

    /** 连不上：连接被拒、超时、DNS 解析失败等。 */
    data class Unreachable(val reason: String) : ProbeResult
}

/** 必须与服务端 HealthController.SERVICE_NAME 一致。 */
private const val SERVICE_NAME = "sms-gateway"

private const val HEALTH_PATH = "api/health"

/**
 * 探活超时（秒）。远短于共享客户端的 30 秒 —— 这是个交互式的「测一下通不通」，
 * 结果出得快比等得久重要；而 8 秒又足够容忍远程部署的握手开销。
 */
private const val PROBE_TIMEOUT_SECONDS = 8L

/** 判错时带回来的响应体片段上限，够看出对面是什么就行，不必整页塞进界面。 */
private const val SNIPPET_LENGTH = 120

/** 探测最多试几次。后两次是给「WiFi 刚重连、本机暂时没有路由」那几秒用的。 */
private const val PROBE_ATTEMPTS = 3

/** 第一次重试前等多久，第二次翻倍（1.5 秒 → 3 秒，共覆盖约 4.5 秒的窗口）。 */
private const val PROBE_RETRY_DELAY_MS = 1500L

/**
 * 一次探测多快算「秒回的失败」，值得重试。
 *
 * 实测 WiFi 重连后会得到 `isConnected failed: EHOSTUNREACH (No route to host)` ——
 * 这种是立刻返回的，说明问题在本机这一侧（没有路由），换个时机就好。
 * 而一次探测耗时逼近超时阈值，说明包发出去了对面没回（防火墙丢包、服务没起），
 * 再试两次只会让按钮多转 16 秒，结论不会变。
 */
private const val PROBE_FAST_FAILURE_MS = 3_000L

/**
 * WiFi 连上多久之内，算「刚连上」，放宽探测的重试预算。
 *
 * 实测「WiFi 拿到 IP」到「局域网真正通」之间约 15 秒，30 秒留了余量 ——
 * 太短会在这台机器上漏掉，太长则会把「网络稳了但对面真的没响应」也拖进重试。
 */
private const val WIFI_SETTLING_MS = 30_000L

/**
 * 刚切网时**总共**允许试几次。
 *
 * 3 次不够：实测失败可能是秒回的「本机没路由」，这时 3 次加起来只覆盖约 5 秒，
 * 而窗口有十几秒 —— 真机上就是这样失败的（点了自检，12 秒时还没结果，最终报「连不上」）。
 * 配上下面的退避与预算，8 次覆盖约 20 秒。
 */
private const val PROBE_SETTLING_ATTEMPTS = 8

/** 刚切网时的探测总预算。到点就放弃，别让界面无限转下去。 */
private const val PROBE_SETTLING_BUDGET_MS = 25_000L

/** 退避上限。别让后面的重试间隔越拉越长，那会白占掉预算里本可以再试一次的时间。 */
private const val PROBE_RETRY_MAX_DELAY_MS = 3_000L

private data class HealthEnvelope(val code: Int, val data: HealthData?)

private data class HealthData(val service: String?, val database: String?)
