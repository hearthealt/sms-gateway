package com.smsgateway.app.network

import android.content.Context
import com.google.gson.Gson
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.util.AuthState
import com.smsgateway.app.util.DevicePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var baseUrl: String = "http://10.0.2.2:8080/"
    private var deviceToken: String? = null
    private var deviceId: String? = null

    private var apiService: ApiService? = null
    private var retrofit: Retrofit? = null
    private var httpClient: OkHttpClient? = null
    private var probeClient: OkHttpClient? = null

    /**
     * Application context，供鉴权拦截器在收到 401 时清理本地令牌。
     * 只存 Application，不会泄漏 Activity。
     */
    private var appContext: Context? = null

    /** 只用于解析探活响应。Retrofit 那个 Gson 实例藏在 GsonConverterFactory 里取不出来。 */
    private val gson = Gson()

    /** Retrofit 要求 baseUrl 以 / 结尾，这里统一收口，免得configure 与 ensureConfigured 各写一份。 */
    private fun normalizedUrl(url: String): String = url.trimEnd('/') + "/"

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
     */
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

    fun updateToken(token: String?) {
        deviceToken = token
        resetClient()
    }

    fun updateDeviceId(id: String?) {
        deviceId = id
        resetClient()
    }

    fun getDeviceToken(): String? = deviceToken
    fun getDeviceId(): String? = deviceId
    fun getBaseUrl(): String = baseUrl

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
                    onUnauthorized = { appContext?.let { AuthState.markTokenRejected(it) } }
                )
            )
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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

    fun getApiService(): ApiService {
        if (apiService == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(getHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit!!.create(ApiService::class.java)
        }
        return apiService!!
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
     */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
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
            ProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
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

private data class HealthEnvelope(val code: Int, val data: HealthData?)

private data class HealthData(val service: String?, val database: String?)
