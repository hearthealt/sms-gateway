package com.smsgateway.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.model.*
import com.smsgateway.app.network.ProbeResult
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.qr.QrIdentity
import com.smsgateway.app.service.GatewayForegroundService
import com.smsgateway.app.util.AuthState
import com.smsgateway.app.util.DeviceName
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.HeartbeatSender
import com.smsgateway.app.util.ServerTime
import com.smsgateway.app.util.UploadEvents
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/** 自检结果的一项。 */
data class SelfTestItem(val label: String, val ok: Boolean, val detail: String)

/**
 * 「快速连接」进行到哪一步。null 表示没有进行中的连接。
 *
 * 分阶段是为了让界面能说出「现在卡在哪一步」—— 探测最坏要 3 × 8 秒（见
 * RetrofitClient.probe），期间只转一个不说话的圈，现场会当成死机。
 */
enum class ConnectStage { PROBING, REGISTERING }

/**
 * 「快速连接」的结论，也是注册本身的结论。
 *
 * @param retryable 值不值得用**已保存的地址**再试一次（不必重新扫码）。
 *   地址已经存下之后的失败（探测不通、注册失败）都是 true；而「地址格式不合法、
 *   什么都没写」那种是 false —— 对着一个没被采用的地址重试，只会让人更迷惑。
 */
data class ConnectOutcome(val ok: Boolean, val message: String, val retryable: Boolean = false)

data class DashboardState(
    val isRunning: Boolean = false,
    /** 空串表示未设置；界面展示用的「未设置」由 composable 负责格式化。 */
    val deviceId: String = "",
    val deviceToken: String = "",
    val phone: String = "",
    /**
     * 注意这里**没有** deviceName。
     *
     * 设备名称跟随手机本身（见 [DeviceName]），是**系统状态**而不是应用状态：
     * 用户随时可能在「设置→关于手机→设备名称」里改，应用也拦不住。
     * 放进这个 state 就等于存了一份会过期的副本 —— 设置页那个可编辑输入框当初
     * 正是这么来的，还顺带让二维码能把别人机器的名字写进来。展示处直接读系统值。
     */
    val serverUrl: String = DevicePrefs.DEFAULT_SERVER_URL,
    /**
     * 当前服务器的接入口令，未设置时为空串。
     *
     * 放在 state 里是为了让「导出到另一台设备」那张二维码能带上它：服务端启用了准入
     * 校验时，不带口令的码扫到另一台手机上会注册失败 —— 导出的本意是「让另一台设备
     * 也能接进这台服务器」，少了口令就不是一份完整的配置。
     */
    val enrollToken: String = "",
    /**
     * 注意这里**没有** serverStatus 字段。
     *
     * 它曾经是个存储字段，但只在注册请求里被写过、且不持久化，于是每次冷启动都退回
     * 「未连接」——哪怕设备已注册、服务在跑、心跳正常，而它下面一行的「最后心跳」
     * 却是实时的，两行自相矛盾。
     *
     * 连接状态应当由实时证据推导（见 MainActivity.serverStatusText）：
     * 是否注册、服务是否在跑、最近一次心跳距今多久。
     */
    /** 最近一次心跳成功的时刻（epoch 毫秒），界面据此算相对时间。 */
    val lastHeartbeatAt: Long? = null,
    /**
     * 网关本次启动的时刻（epoch 毫秒），没在跑时为 null。
     *
     * 与 [lastHeartbeatAt] 配套：那个回答「上次成功是什么时候」，这个回答「这次是什么
     * 时候起来的」。两个都要有才分得出「刚起来、正在连」和「起来很久了、一次都没连上」
     * —— 后者的 lastHeartbeatAt 也是 null，只看它会把一台断了三天的设备说成「刚启动」。
     */
    val gatewayStartedAt: Long? = null,
    val pendingCount: Int = 0,
    val todaySmsCount: Int = 0,
    val todayCodeCount: Int = 0,
    val isRegistering: Boolean = false,
    val registerMessage: String? = null,
    /**
     * 「快速连接」进行到哪一步，null = 没有进行中的连接。界面据此显示进度。
     */
    val connectStage: ConnectStage? = null,
    /**
     * 探测正在第几次尝试（从 1 开始）。只在 [connectStage] == PROBING 时有意义。
     *
     * 单独一个字段而不是并进 ConnectStage：那是个枚举，而这里要的是「同一个阶段里的
     * 第几次」。也不会进注册阶段 —— 注册不做重试。
     */
    val connectAttempt: Int = 1,
    /**
     * 「快速连接」的一次性结论，由扫码连接页取走后立即清空。
     *
     * 单独一个字段而不是复用 registerMessage：那条通道的消费者是 GatewayApp，
     * 它把消息变成一闪而过的 snackbar。而失败原因（地址不对 / 对面不是本服务 /
     * 口令被拒）需要**留在屏幕上**让人照着处理，不该自己消失。
     */
    val connectResult: ConnectOutcome? = null,
    /**
     * 被禁用横幅上「检查状态」的结论。
     *
     * **只给那个横幅用。** 它是持久展示的：禁用状态下用户要拿它留在屏幕上对照，
     * 所以不该走一闪而过的通道。设置页原先也往这里写，结果是「地址格式不合法」
     * 一直挂在页面上擦不掉 —— 共享一个持久字段就会这样。
     */
    val testResult: String? = null,

    /**
     * 设置页的一次性提示：清理本地记录的结果等。设置页消费后立即清空，以 snackbar 一闪而过。
     *
     * 单独一个字段而不是复用上面的 testResult：那个是持久展示的，
     * 写进去的消息不会自己消失，会在页面上留一句擦不掉的残留。
     */
    val settingsMessage: String? = null,
    /** 被管理员禁用：上传会被服务端拒绝，界面要明说，不能让人以为一切正常。 */
    val isDisabled: Boolean = false,

    // 队列页（本地未上传的行）
    val queue: List<SmsQueueEntity> = emptyList(),
    val queueLoading: Boolean = false,

    /**
     * 主页「最近收到」用的几条：服务端最新 3 条。
     *
     * 与 [smsRecords]（记录页那一份，带分页）分开：这份只服务主页那三行摘要，
     * 不能因为主页要看一眼就把记录页的分页状态搅乱。
     */
    val recentSms: List<SmsRecord> = emptyList(),

    /**
     * 最后一次自检的摘要（如「6 项全部通过」）与时刻，没跑过时为空串 / 0。
     *
     * 主页那行「自检」拿它显示结论 —— 现场不用点进去等六项跑完就知道还过不过。
     */
    val lastSelfTest: String = "",
    val lastSelfTestAt: Long = 0L,

    /**
     * 待写入剪贴板的内容（一次性的）。
     *
     * 剪贴板要 Context，而这里走「ViewModel 备好内容 → 界面写剪贴板并清空」这条路，
     * 与 registerMessage / settingsMessage 同一套一次性通道。
     * 空串是有效值：表示「今天一条验证码都没有」，界面据此提示而不是复制一段空白。
     */
    val copyPayload: String? = null,

    // 服务端记录页
    val smsRecords: List<SmsRecord> = emptyList(),
    val smsTotal: Long = 0,
    /** 当前已加载到第几页（从 1 开始）。「加载更多」读它 +1。 */
    val smsPage: Int = 1,
    val smsLoading: Boolean = false,
    val smsError: String? = null,

    // 自检页
    val selfTest: List<SelfTestItem> = emptyList(),
    val selfTestRunning: Boolean = false
) {
    /**
     * 派生量，判据与 DevicePrefs.isRegistered 完全一致。
     *
     * 不单独存字段：这个 state 有很多处 copy()，漏写一处就会与真实注册状态脱节，
     * 而界面门禁正是靠它决定主按钮能不能点。
     */
    val isRegistered: Boolean get() = deviceId.isNotBlank() && deviceToken.isNotBlank()
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val KEY_DEVICE_TOKEN = DevicePrefs.KEY_DEVICE_TOKEN
        private const val KEY_PHONE = DevicePrefs.KEY_PHONE
        private const val KEY_SERVER_URL = DevicePrefs.KEY_SERVER_URL

        private const val SMS_PAGE_SIZE = 20

        /** 主页「最近收到」显示几条。三行足够看出「还在收」，再多就把主页撑回一屏列表了。 */
        private const val RECENT_SMS_COUNT = 3

        /**
         * 「复制今日验证码」拉多少条。
         *
         * 设备接口没有日期筛选参数，只能取一页再按日期过滤 —— 100 条够一天的量，
         * 今天超过 100 条时会少几个（现场联调够用，要全量有服务端记录页）。
         */
        private const val COPY_CODES_PAGE_SIZE = 100
    }

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val gson = Gson()

    /**
     * 注册请求的并发闸门。
     *
     * 用 AtomicBoolean 而不是普通布尔：后者只有在「所有调用方都在主线程」时才成立，
     * 而这个前提没有任何地方保证。这里守卫的是一次 check-and-set，用 Mutex 反而过重。
     */
    private val registerInFlight = AtomicBoolean(false)

    /**
     * 快速连接的并发闸门。理由同上：连点两次确认会串出两轮「探测 → 注册」，
     * 而第二轮探测用的可能已经是第一轮刚写进去的地址。
     */
    private val connectInFlight = AtomicBoolean(false)

    private val database = AppDatabase.getInstance(application)
    private val prefs = DevicePrefs.get(application)

    init {
        loadSavedState()
        restoreRetrofitConfig()
        tryAutoFillPhone()
        ensureGatewayServiceRunning()
        startMonitoring()
        observeService()
        observeUploads()
    }

    /**
     * 对账：prefs 说「网关该在跑」，那就确保前台服务真的在跑。
     *
     * 这一步补的是一个死锁，症状是「启动过网关 → 上滑关掉 App → 过一会进来，
     * 界面卡在『已启动，等待心跳』，而后端一直显示离线」：
     *
     * 1. 上滑清理杀掉进程时 `onDestroy` 不会执行，所以 prefs 里 `gateway_running`
     *    仍是 true —— 界面据此显示「已启动」，而服务早就没了；
     * 2. 系统那条 `START_STICKY` 在没有电池白名单的机器上未必把服务拉回来
     *    （主页那条橙色横幅提示的就是这件事）；
     * 3. 于是没有任何人在发心跳，而**重开 App 这条路径上原先是没有任何一行代码
     *    会把服务重新拉起来的** —— `GatewayForegroundService.start` 只被开机广播
     *    和用户点开关调用。开关此时还会反着来：它读的是持久化标志（true），
     *    点下去走的是「停止」，对着一个不存在的服务调 stopService，界面毫无反应。
     *
     * 放在这里而不是 MainActivity：服务的运行态本来就由 ViewModel 订阅并展示，
     * 「该在跑」与「真的在跑」的对账属于同一件事。此刻界面已在前台，
     * Android 12+ 对前台服务启动的限制也不适用。
     */
    private fun ensureGatewayServiceRunning() {
        val app = getApplication<Application>()
        // 未注册就没得可跑：服务起来了也只会每 30 秒空转一次（发送器自己会跳过）
        if (!DevicePrefs.isRegistered(app)) return
        if (GatewayState.isRunning(app)) {
            GatewayForegroundService.start(app)
        }
    }

    /**
     * 上传成功是**本地事件**：立刻重算「待上传」并拉一次服务端统计，不等轮询。
     *
     * 上传其实很快（实测从收到短信到入库 7~19 秒），而轮询是 5 秒看一次本地队列、
     * 30 秒拉一次服务端统计。于是现场看到的是「验证码到了，待上传还是 0，
     * 直到下一次心跳今日短信才 +1」—— 中间那段完全看不出这条码进没进来。
     */
    private fun observeUploads() {
        viewModelScope.launch {
            UploadEvents.successCount.collect { count ->
                // 订阅时会先收到当前值，那不是「刚刚发生」的事件
                if (count == 0L) return@collect
                refreshPendingCount()
                refreshServerStats()
                // 刚传上去一条 —— 主页那三行摘要立刻跟上，不等下一个 30 秒
                refreshRecentSms()
            }
        }
    }

    /** 服务运行态、心跳时间、禁用状态都由服务/发送器产生，这里只订阅它们用于展示。 */
    private fun observeService() {
        viewModelScope.launch {
            GatewayState.running.collect { running ->
                _state.update { it.copy(isRunning = running) }
            }
        }
        viewModelScope.launch {
            GatewayState.startedAt.collect { at ->
                _state.update { it.copy(gatewayStartedAt = at) }
            }
        }
        viewModelScope.launch {
            HeartbeatSender.lastSuccessAt.collect { at ->
                _state.update { it.copy(lastHeartbeatAt = at) }
            }
        }
        viewModelScope.launch {
            DeviceStatus.disabled.collect { disabled ->
                // 解除禁用的同时清掉上一次的检查结论。留着的话，这台设备**下次**再被
                // 禁用时，横幅一冒出来就顶着一句「已恢复」—— 那是上一轮的结论，
                // 而用户还没点过任何按钮。
                _state.update {
                    it.copy(
                        isDisabled = disabled,
                        testResult = if (disabled) it.testResult else null
                    )
                }
            }
        }

        // 服务端拒绝了令牌（设备被删、或换了主密钥）。AuthState 已经把本地令牌清掉，
        // 而 isRegistered 是 deviceId/deviceToken 的派生属性，所以界面上的「已注册」
        // 会自动换成「设备未注册」；这里再补一句说明，免得用户以为是自己点错了什么。
        viewModelScope.launch {
            AuthState.tokenRejected.collect { rejected ->
                if (!rejected) return@collect
                _state.update {
                    it.copy(
                        deviceId = DevicePrefs.deviceId(getApplication()),
                        deviceToken = "",
                        // 说清去哪儿重注册。设置页那句「什么时候该点重新注册」的常驻说明
                        // 已经删掉了，指路就落在这条消息上 —— 它出现在出问题的那一刻，
                        // 比一条平时没人看的说明有用。
                        registerMessage = "服务端已不认这台设备，请到「设置」里点重新注册"
                    )
                }
                AuthState.consume()
            }
        }
    }

    private fun loadSavedState() {
        val app = getApplication<Application>()
        // 网关运行态的真源在 prefs 里（见 GatewayState），这里先水合再订阅，
        // 否则订阅到的是内存初值 false，界面会先闪一下「已停止」。
        GatewayState.ensureLoaded(app)
        // 上一次心跳的时间也一起水合：否则进程重建后它归零，界面显示「服务刚起来」，
        // 看不出设备是刚启动还是已经断了十分钟
        HeartbeatSender.ensureLoaded(app)
        _state.update {
            it.copy(
                // 用「取或生成」而不是纯读：设备身份在启动这一步就落定，不再等注册。
                //
                // 这不只是显示问题。设备名末尾要缀一段设备标识（见 DeviceName），
                // 而注册请求里带的正是这个名字 —— 等到注册时才生成的话，注册那一刻
                // 还没有标识，后台先记成「Redmi K60 Ultra」，之后第一次心跳又变成
                // 「Redmi K60 Ultra · a5362900」。同型号两台机器在后台本来就分不出来，
                // 而这段后缀存在的唯一理由就是分辨它们，偏偏在最该起作用的那一刻缺席。
                //
                // 值取自 SSAID（见 newDeviceId），启动时生成与注册时生成完全一致，
                // 所以服务端不会因此多出一条设备记录。
                deviceId = DevicePrefs.getOrCreateDeviceId(app),
                deviceToken = DevicePrefs.deviceToken(app),
                phone = DevicePrefs.phone(app),
                serverUrl = DevicePrefs.serverUrl(app),
                enrollToken = DevicePrefs.enrollToken(app),
                isDisabled = DevicePrefs.isDisabled(app),
                // 上次自检的结论也一起水合：主页那行「自检」靠它显示「6 项全部通过 · 2 小时前」
                lastSelfTest = DevicePrefs.lastSelfTest(app),
                lastSelfTestAt = DevicePrefs.lastSelfTestAt(app)
            )
        }
    }

    /**
     * 保存服务器地址。默认值 10.0.2.2 只是模拟器访问宿主机的别名，
     * 真机必须改成后端所在机器的局域网 IP，所以这里必须可配置。
     */
    fun updateServerUrl(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').ifBlank { DevicePrefs.DEFAULT_SERVER_URL }

        // 必须在这里挡住非法地址，因为它一旦落盘就很难收拾：Retrofit 是懒构造的，
        // 直到下一次心跳/注册才拿它去建 baseUrl 并抛 IllegalArgumentException ——
        // 那时崩溃点离「刚敲错一个字」已经很远，而且地址还在 prefs 里，每次启动都崩，
        // 用户只能清应用数据。所以宁可存不进去。
        if (!RetrofitClient.isValidBaseUrl(normalized)) {
            // 不写任何状态字段：失败由按钮那边用 snackbar 一闪而过地报出。
            // 写进状态就会在页面上留一句擦不掉的残留，之前正是这个问题。
            return false
        }

        applyServerUrl(getApplication(), normalized)
        return true
    }

    /**
     * 写入服务器地址；**换了服务器就清掉令牌**。
     *
     * 令牌是在旧服务器上签发的，留着它会让 isRegistered 仍为 true、App 启动网关
     * 并对着新服务器一直 401。设备标识保留，所以重新注册仍是同一台设备。
     */
    private fun applyServerUrl(context: Context, normalized: String) {
        val changed = normalized != DevicePrefs.serverUrl(context)

        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        if (changed) {
            DevicePrefs.clearToken(context)
            prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
            // 接入口令是**某台服务器**签发的，跟着地址一起作废：带到新服务器上既没用，
            // 又是一次没必要的泄露。
            DevicePrefs.clearEnrollToken(context)
        }
        RetrofitClient.ensureConfigured(context)

        _state.update {
            it.copy(
                serverUrl = normalized,
                deviceToken = if (changed) "" else it.deviceToken,
                // 口令与地址同进退：盘上那份已在上面清掉了，state 这份也要跟上，
                // 否则「导出到另一台设备」会继续把上一台服务器的口令印进二维码。
                enrollToken = if (changed) "" else it.enrollToken,
                registerMessage = if (changed) {
                    "服务器地址已变更，请重新注册设备"
                } else {
                    it.registerMessage
                }
            )
        }
    }

    /**
     * 采用二维码里的设备身份 —— 即管理员在控制台签发的恢复码。
     *
     * 这是设备重装（本地密钥随应用数据一起没了）、或老设备首次启用重注册校验之后，
     * 唯一能取回身份的途径。设备标识与重注册密钥一起覆盖，旧令牌一并清掉：
     * 令牌是签发在**旧身份**上的，留着会让 isRegistered 仍为真、App 拿着它一路 401。
     *
     * @return 地址合法并已写入时 true；false 表示地址不合法，什么都没改。
     */
    fun adoptEnrollIdentity(deviceId: String, enrollSecret: String, serverUrl: String): Boolean {
        val normalized = serverUrl.trim().trimEnd('/').ifBlank { DevicePrefs.DEFAULT_SERVER_URL }
        if (!RetrofitClient.isValidBaseUrl(normalized)) return false

        val app = getApplication<Application>()
        DevicePrefs.adoptEnrollIdentity(app, deviceId, enrollSecret)
        applyServerUrl(app, normalized)

        _state.update {
            it.copy(
                deviceId = DevicePrefs.deviceId(app),
                // **无条件清掉**，不能只靠 applyServerUrl 里那个 `changed` 分支。
                // DevicePrefs.adoptEnrollIdentity 已经用 commit() 删了盘上的令牌，而这里
                // 在「二维码里的地址与当前已保存地址相同」时 changed == false，state 里
                // 那一份就被留下来了 —— 于是 isRegistered 报 true、界面显示「已注册」，
                // 而每个请求都 401。扫的正好是当前这台服务器的恢复码时必然触发。
                deviceToken = "",
                registerMessage = "已采用恢复码中的设备身份，请重新注册设备"
            )
        }
        return true
    }

    private fun restoreRetrofitConfig() {
        RetrofitClient.ensureConfigured(getApplication())
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                refreshPendingCount()

                // 今日统计则必须来自服务端：设备端的记录页展示的就是服务端数据，
                // 本地库会因为「清理本地记录」、清除应用数据而与它不一致，
                // 之前正是这样出现了「数字显示 0、点进去却有内容」。
                // 每 6 个 tick（约 30 秒）拉一次即可，不必跟着 5 秒的本地轮询 ——
                // 真正需要「立刻」的那种变化（刚传上去一条）走 UploadEvents，不靠这里。
                if (ticks % 6 == 0) {
                    refreshServerStats()
                    refreshRecentSms()
                }
                ticks++

                delay(5_000L)
            }
        }
    }

    /**
     * 重算「待上传」。这是本地队列的真实积压量，只能读本地库 ——
     * 服务端不知道这台设备还有多少条没传上去。
     *
     * 必须用 update{} 而不是 `_state.value = _state.value.copy(...)`。
     *
     * getOutstandingCountSync() 是 suspend 的，会挂起并让出主线程；而
     * `_state.value = _state.value.copy(pendingCount = count)` 这种写法里，
     * 接收者 `_state.value` 是在**挂起之前**就被求值的，恢复之后写回去的
     * 是挂起那一刻的旧快照 —— 挂起期间别的收集器写进去的字段会被原样抹掉。
     *
     * 现场表现（杀进程后重开 app 时必现）：ViewModel 刚建好时服务尚未重启，
     * 快照里 isRunning=false；查询挂起期间系统的 START_STICKY 把服务拉了起来、
     * 收集器把 isRunning 写成 true；查询一恢复，这行就把 true 覆盖回 false。
     * 此后 isRunning 不再变化，界面永远停在「网关已停止」，而服务其实在跑 ——
     * 点「启动网关」只会给一个已在运行的服务补发一次 start，
     * onStartCommand 不改变运行态，界面因此毫无反应。
     */
    private suspend fun refreshPendingCount() {
        try {
            val count = database.smsQueueDao().getOutstandingCountSync()
            _state.update { it.copy(pendingCount = count) }
        } catch (e: Exception) {
            Log.e(TAG, "Load pending count failed", e)
        }
    }

    /**
     * 拉取服务端的今日统计。
     *
     * 失败时**保留上一次的数字**，不清零 —— 网络抖一下就把计数变成 0，
     * 比显示一个稍旧的数字更让人困惑。
     */
    private suspend fun refreshServerStats() {
        val app = getApplication<Application>()
        if (!DevicePrefs.isRegistered(app)) return

        try {
            val stats = RetrofitClient.getApiService().mySmsStats().body()?.data ?: return
            _state.update {
                it.copy(
                    todaySmsCount = stats.todaySms.toInt(),
                    todayCodeCount = stats.todayCodes.toInt()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Load server stats failed", e)
        }
    }

    fun clearRegisterMessage() {
        _state.update { if (it.registerMessage == null) it else it.copy(registerMessage = null) }
    }

    /**
     * 尽力自动预填本机号码。
     *
     * 只在用户还没填过号码时才写入 —— 手填的值优先级更高，不能被一次自动读取覆盖掉。
     * 多数 SIM 卡并没有写入本机号码，读不到是常态；此时什么都不做，等用户在设置页手填。
     */
    fun tryAutoFillPhone() {
        val app = getApplication<Application>()
        val existing = DevicePrefs.phone(app)
        if (existing.isNotBlank()) {
            backfillPhoneSubId(app, existing)
            return
        }

        // 优先按键列出卡再读：这样能一并记住号码属于哪张卡（多卡时判断归属性要用）
        val slot = DevicePhone.primarySlot(app)
        if (slot?.number != null) {
            DevicePrefs.setPhone(app, slot.number, slot.subscriptionId)
            _state.update { it.copy(phone = slot.number) }
            return
        }

        // 列不出卡时退回默认读取，此时无从得知号码属于哪张卡
        val number = DevicePhone.read(app) ?: return
        DevicePrefs.setPhone(app, number, -1)
        _state.update { it.copy(phone = number) }
    }

    /**
     * 补上「号码属于哪张卡」。
     *
     * 号码本身**不动**（手填的值优先，也可能是用户改过的），只在能确定「盘上那个号码
     * 就是这张卡读出来的」时才把 subId 补进去。要补的原因：早期版本列不出 SIM 卡时
     * 走的是默认读取那条路，subId 只能记 -1，而 [SmsReceiver.resolveSmsPhone] 正是
     * 拿它判断「这条短信是不是来自另一张卡」—— 恒为 -1 等于这个判断永远不成立，
     * 一条从副卡进来的验证码会被记成主卡的号码，而服务端是按号码缓存验证码的。
     */
    private fun backfillPhoneSubId(app: Application, existing: String) {
        if (DevicePrefs.phoneSubId(app) >= 0) return

        val slot = DevicePhone.primarySlot(app) ?: return
        val number = slot.number ?: return
        if (!DevicePhone.sameNumber(number, existing)) return

        DevicePrefs.setPhone(app, number, slot.subscriptionId)
    }

    /**
     * 设置本机号码。
     *
     * @param subId 号码来自哪张卡；从 SIM 选择器读来的会带上，手动填写则传 -1。
     *              记它是因为多卡时要靠它判断「一条短信是不是来自另一张卡」。
     */
    fun updatePhone(raw: String, subId: Int = -1) {
        val app = getApplication<Application>()
        val normalized = raw.trim()

        // 文本没变就沿用原有的来源卡信息：用户从 SIM 卡读完之后顺手点一下「保存手机号」，
        // 不该把「这个号码来自哪张卡」抹掉 —— 抹掉之后多卡时又无法判断归属性了。
        val effectiveSubId =
            if (normalized == DevicePrefs.phone(app)) DevicePrefs.phoneSubId(app) else subId

        DevicePrefs.setPhone(app, normalized, effectiveSubId)
        _state.update { it.copy(phone = normalized) }
    }

    /** 注册时上报的设备名：跟随手机本身，读不到由 [DeviceName] 兜底。 */
    private fun effectiveDeviceName(): String = DeviceName.read(getApplication())

    fun registerDevice() {
        // 连点两下曾会发出两个并发请求、各生成一个随机设备号，服务端于是多出一台「新设备」。
        if (!registerInFlight.compareAndSet(false, true)) return

        _state.update { it.copy(isRegistering = true, registerMessage = null) }

        viewModelScope.launch {
            try {
                _state.update { it.copy(registerMessage = runRegistration().message) }
            } finally {
                registerInFlight.set(false)
                _state.update { it.copy(isRegistering = false) }
            }
        }
    }

    /**
     * 跑一次注册，把成败翻成一句给现场看的话。
     *
     * 异常一律在这里收口，于是调用方只剩「把这句话放上自己的通道」一件事 ——
     * 而两条通道的去处不同：设置页那条是一闪而过的 snackbar，快速连接那条要留在
     * 扫码页上让人照着排查。共用前半段、只在展示处分叉，才不会两边各写一遍 try/catch。
     */
    private suspend fun runRegistration(): ConnectOutcome = try {
        performRegistration()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.e(TAG, "Registration failed: server unreachable", e)
        ConnectOutcome(false, "连不上服务器，请到设置里检查服务器地址")
    } catch (e: Exception) {
        Log.e(TAG, "Registration failed", e)
        ConnectOutcome(false, "注册失败：${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 真正发注册请求。
     *
     * **刻意不写 registerMessage**：由调用方决定这条结论走哪条通道（理由见 [runRegistration]）。
     */
    private suspend fun performRegistration(): ConnectOutcome {
        val app = getApplication<Application>()

        // 先落盘再发请求。UUID 一旦生成就是持久的，即使这次请求失败或进程中途被杀，
        // 重试复用的也是同一个标识，不会再注册出第二台设备。
        val deviceId = DevicePrefs.getOrCreateDeviceId(app)
        _state.update { it.copy(deviceId = deviceId) }

        val phone = DevicePrefs.phone(app).ifBlank { null }

        val response = RetrofitClient.getApiService().registerDevice(
            DeviceInfo(
                deviceId = deviceId,
                deviceName = effectiveDeviceName(),
                platform = "android",
                phone = phone,
                appVersion = BuildConfig.VERSION_NAME,
                enrollSecret = DevicePrefs.getOrCreateEnrollSecret(app),
                // 服务端只在**首次注册**时看它，已注册的设备带着也无妨。
                // 为空表示这台服务器没启用准入校验（或是老用户没扫过码），服务端会放行。
                enrollToken = DevicePrefs.enrollToken(app).ifBlank { null }
            )
        )

        val data = response.body()?.data
        if (!response.isSuccessful || data == null) {
            // 这里原本读 body()?.message —— 但 Retrofit 在非 2xx 时 body() 恒为 null，
            // 载荷其实在 errorBody() 里，不解析就永远只能显示一个光秃秃的状态码。
            val detail = parseErrorMessage(response.errorBody()?.string())
            return ConnectOutcome(
                false,
                "注册失败（HTTP ${response.code()}）" + (detail?.let { "：$it" } ?: ""),
                retryable = true
            )
        }

        val token = data.deviceToken
        RetrofitClient.updateToken(token)
        RetrofitClient.updateDeviceId(deviceId)

        prefs.edit().apply {
            putString(KEY_DEVICE_TOKEN, token)
            if (phone != null) putString(KEY_PHONE, phone)
        }.apply()

        // 注册响应带着设备状态，这里同步一次：被禁用的设备重新注册后仍是禁用，
        // 不该因为「注册成功了」就显示成可用。
        DeviceStatus.set(app, data.status.equals("DISABLED", ignoreCase = true))

        // 注册前收到的短信是以空 deviceId/phone 入库的，先把身份补上再触发上传。
        try {
            database.smsQueueDao().backfillIdentity(deviceId, phone.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "Backfill queue identity failed", e)
        }

        _state.update { it.copy(deviceToken = token, phone = phone.orEmpty()) }

        SmsUploadWorker.enqueue(app)
        refreshServerStats()
        startService()

        // 成功也要说一声。之前成功分支置 null，于是点了「重新注册」之后界面上什么都不变 ——
        // 现场无从判断到底成没成，只能靠猜。
        return ConnectOutcome(true, "注册成功")
    }

    /** 从错误响应体里取出后端给的 message。解析失败就当没有，不因此再抛一次。 */
    private fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(raw, ErrorBody::class.java)?.message?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class ErrorBody(val code: Int = 0, val message: String? = null)

    /**
     * 「快速连接」：扫码拿到服务器地址后，一气做完 保存地址 → 测试连接 → 注册设备。
     *
     * <p>三步必须在 ViewModel 里串，**不能在 Composable 里逐个调用**：
     * `testConnection` 的结论是异步落到 state 上的，界面拿不到那个布尔值，也就无从
     * 决定要不要继续注册；而且两条路各自的闸门（`testInFlight` / `registerInFlight`）
     * 会交错，连点两下能串出一次「先注册后探测」。
     *
     * <p>探测这一步不是可有可无的仪式：地址填错时它能说出「有响应但不是本服务」
     * 或「连接被拒绝」，而注册失败只会给一句笼统的「连不上服务器」。
     *
     * @param enrollToken 二维码里带的服务器接入口令，没带时传 null。
     * @param identity    二维码里带的设备身份（即恢复码）。非空时先采用它再注册 ——
     *                    重装过的手机也走这个页面，扫的正是管理员签的那张恢复码，
     *                    在这里拒收会把它变成一条死路。
     */
    fun quickConnect(url: String, enrollToken: String?, identity: QrIdentity? = null) {
        if (!connectInFlight.compareAndSet(false, true)) return

        _state.update { it.copy(connectStage = ConnectStage.PROBING, connectAttempt = 1, connectResult = null) }

        viewModelScope.launch {
            try {
                // 保存。两条路都可能失败，且失败时**什么都不写**（见各自注释），
                // 所以这里必须自己把结论报出来，否则界面会静默无反应。
                val saved = if (identity != null) {
                    adoptEnrollIdentity(identity.deviceId, identity.enrollSecret, url)
                } else {
                    updateServerUrl(url)
                }
                if (!saved) {
                    _state.update {
                        it.copy(
                            connectStage = null,
                            connectResult = ConnectOutcome(false, "地址格式不合法，未导入")
                        )
                    }
                    return@launch
                }

                // 上面两步都会顺手写一句「请重新注册设备」——那是给**单独导入配置**那条路
                // 用的提示，而这里紧接着就注册了。不抹掉的话，GatewayApp 会把那句已经过期
                // 的话弹成 snackbar，现场看到的是「刚说连接成功，又让我去重新注册」。
                _state.update { it.copy(registerMessage = null) }

                // 口令跟着地址一起存。顺序不能反：applyServerUrl / adoptEnrollIdentity
                // 换地址时会清掉旧口令（它是上一台服务器签发的）。
                val token = enrollToken.orEmpty()
                DevicePrefs.setEnrollToken(getApplication(), token)
                _state.update { it.copy(enrollToken = token) }

                // 把「第几次尝试」实时喂给界面：刚连上 WiFi 时那次探测可能要重试几轮、
                // 每轮最长 8 秒，只转一个不说话的圈现场会以为死机（然后去杀进程）。
                val (ok, detail) = describeProbe(
                    RetrofitClient.probe(url) { attempt ->
                        _state.update { it.copy(connectAttempt = attempt) }
                    }
                )
                if (!ok) {
                    _state.update {
                        it.copy(connectStage = null, connectResult = ConnectOutcome(false, detail))
                    }
                    return@launch
                }

                _state.update {
                    it.copy(connectStage = ConnectStage.REGISTERING, isRegistering = true)
                }
                val outcome = runRegistration()
                _state.update { it.copy(connectStage = null, connectResult = outcome) }
            } finally {
                connectInFlight.set(false)
                // 取消（离开页面、进程被回收）时也要把进行中状态放掉，
                // 否则下次进这个页面会看到一个永远转不完的圈。
                _state.update { it.copy(connectStage = null, isRegistering = false) }
            }
        }
    }

    /**
     * 重试上一次的快速连接：用**已保存的**地址与口令重跑一遍探测 + 注册，不必重新扫码。
     *
     * 加它是为了让「刚连上 WiFi、局域网还没就绪」那个窗口不再等于白扫一次：实测那段时间
     * 可能有二十几秒（关闭 WiFi 再连那次量到 28 秒仍然不通），任何预算都可能不够，
     * 而重新扫一次二维码本身又要几秒 —— 一键重试比加大预算更管用。
     *
     * 地址与口令都从 prefs 取，不经过二维码：探测失败时它们已经落盘了
     * （见 quickConnect 里「先保存再探测」的顺序）。重跑时地址没变，
     * applyServerUrl 里那条「换地址就清令牌」的分支不会触发，令牌是安全的。
     */
    fun retryQuickConnect() {
        val app = getApplication<Application>()
        quickConnect(
            url = DevicePrefs.serverUrl(app),
            enrollToken = DevicePrefs.enrollToken(app).ifBlank { null }
        )
    }

    /** 扫码连接页展示完结论后调用，避免下次进这个页面又冒出来。 */
    fun clearConnectResult() {
        _state.update { if (it.connectResult == null) it else it.copy(connectResult = null) }
    }

    // 这里原先有一个给设置页「测试连接」按钮用的 testConnection()。设置页的那个按钮
    // 已经删掉了 —— 它和「扫一扫」里的探测是同一件事，但结论只走一闪而过的
    // settingsMessage，而那段流程的结论是留在页面上的。留着它就是留一条
    // 「两个入口、两套待遇」的路。探活能力本身没丢：扫一扫每次连接都会探一次。

    /** 设置页展示完提示后调用，避免下次进设置页又冒出来。 */
    fun clearSettingsMessage() {
        _state.update { if (it.settingsMessage == null) it else it.copy(settingsMessage = null) }
    }

    /**
     * 把探测结果翻成给现场人员看的一行字，第二项是「是否通过」。
     *
     * 自检页原先给「服务器连通」写死了 true —— 只要 probe 没抛异常就报通过，拿到 404
     * 也打绿勾。这里统一由结果决定，「设置页」和「自检页」两个调用点不再各判各的。
     */
    private fun describeProbe(result: ProbeResult): Pair<Boolean, String> = when (result) {
        is ProbeResult.Online ->
            if (result.databaseUp) {
                true to "已连通（服务正常）"
            } else {
                // 判为不通过：服务器活着但库挂了时，注册和上报全会失败，
                // 显示成一切正常比显示成连不上更误事。
                false to "服务在线，但它的数据库连不上，注册和上报都会失败"
            }

        is ProbeResult.NotOurService ->
            false to "有响应但不是本服务（HTTP ${result.httpCode}），检查地址和端口是否填错"

        is ProbeResult.Unreachable ->
            false to "连不上：${result.reason}"
    }

    // ---------- 队列页 ----------

    fun refreshQueue() {
        viewModelScope.launch { refreshQueueNow() }
    }

    /**
     * 队列读取的挂起版本。
     *
     * 下拉刷新要「等这次读完再收手」，而 [refreshQueue] 只是把活派给 viewModelScope
     * 就返回了 —— 从外面看它永远是瞬时完成的，转圈会在数据回来之前就被收掉。
     * 所以真正干活的是这个版本，[refreshQueue] 退化成一层壳。
     */
    suspend fun refreshQueueNow() {
        _state.update { it.copy(queueLoading = true) }
        try {
            val rows = database.smsQueueDao().getOutstanding()
            _state.update { it.copy(queue = rows, queueLoading = false) }
        } catch (e: Exception) {
            Log.e(TAG, "Load queue failed", e)
            _state.update { it.copy(queueLoading = false) }
        }
    }

    /** 手动重试单条：立刻可传并清零重试计数，然后唤醒 worker。 */
    fun retrySms(id: Long) {
        viewModelScope.launch {
            try {
                database.smsQueueDao().retryNow(id)
                SmsUploadWorker.enqueue(getApplication())
                refreshQueueNow()
            } catch (e: Exception) {
                Log.e(TAG, "Retry SMS $id failed", e)
            }
        }
    }

    fun deleteSms(id: Long) {
        viewModelScope.launch {
            try {
                database.smsQueueDao().deleteById(id)
                refreshQueueNow()
            } catch (e: Exception) {
                Log.e(TAG, "Delete SMS $id failed", e)
            }
        }
    }

    /** 清空本地已上传记录（一直没人调用的 deleteOldRecords 终于接上了）。 */
    fun clearUploadedRecords() {
        viewModelScope.launch {
            try {
                val removed = database.smsQueueDao().deleteAllUploaded()
                Log.i(TAG, "Cleared $removed uploaded rows")
                _state.update { it.copy(settingsMessage = "已清理 $removed 条已上传记录") }
            } catch (e: Exception) {
                // 以前这里只写日志：清理失败时界面毫无反应，用户会以为按钮没生效，
                // 然后一直点。失败也必须说出来。
                Log.e(TAG, "Clear uploaded failed", e)
                _state.update {
                    it.copy(settingsMessage = "清理失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------- 主页「复制今日验证码」 ----------

    /**
     * 备好「今日全部验证码」的文本，交给界面写剪贴板。
     *
     * 走服务端记录接口而不是本地库：本地库里只有**还没传上去**的行，传上去就删了，
     * 拿它拼「今日验证码」只会拼出一个几乎为空的列表。
     *
     * 只取第一页 100 条再按日期过滤（设备接口没有日期筛选参数）。今天超过 100 条时
     * 会少几个 —— 现场联调那种场景够用，真要全量有服务端记录页。宁可这样，
     * 也不为了「凑满」去翻页：那会把一个「点一下给我码」的动作变成拉几十个请求。
     */
    fun requestCopyTodayCodes() {
        viewModelScope.launch {
            val lines = try {
                val response = RetrofitClient.getApiService()
                    .mySms(page = 1, pageSize = COPY_CODES_PAGE_SIZE, includeIgnored = false)
                val records = response.body()?.data?.records.orEmpty()
                val today = LocalDate.now()
                records
                    .filter { record ->
                        !record.code.isNullOrBlank() &&
                            ServerTime.toLocalDate(record.receiveTime) == today
                    }
                    .map { "${it.sender.orEmpty().ifBlank { "未知" }} ${it.code}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Load today codes failed", e)
                emptyList()
            }

            // 空列表也要把 copyPayload 置上（空串）：界面据此说「今天还没有验证码」，
            // 而不是静默什么都不发生 —— 那种「点了没反应」最难查。
            _state.update { it.copy(copyPayload = lines.joinToString("\n")) }
        }
    }

    /** 界面写完剪贴板后调用。 */
    fun clearCopyPayload() {
        _state.update { if (it.copyPayload == null) it else it.copy(copyPayload = null) }
    }

    // ---------- 主页「最近收到」 ----------

    /**
     * 拉服务端最新几条，供主页那三行摘要。
     *
     * 复用记录页那个接口（只取第一页的前几条），不新开接口：这是**看一眼**的东西，
     * 不值得为它加一个后端端点。失败就静默留着上一次的结果 —— 主页的摘要过期几秒
     * 比冒一句报错强，真要排查有记录页和服务端记录页两处。
     */
    private suspend fun refreshRecentSms() {
        if (!DevicePrefs.isRegistered(getApplication())) return
        try {
            val response = RetrofitClient.getApiService()
                .mySms(page = 1, pageSize = RECENT_SMS_COUNT, includeIgnored = true)
            val records = response.body()?.data?.records ?: return
            _state.update { it.copy(recentSms = records.take(RECENT_SMS_COUNT)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Load recent sms failed", e)
        }
    }

    // ---------- 服务端记录页 ----------

    fun loadServerSms(page: Int = 1) {
        viewModelScope.launch { loadServerSmsNow(page) }
    }

    /**
     * 「加载更多」：把下一页接在现有列表后面。
     *
     * 已经有请求在飞就不发（列表底部那个按钮会被连点），读到最后一页也不发 ——
     * 这两个判断都基于调用瞬间的状态，而真正的并发保护在 [_state] 的原子更新里
     * （append 走 `current.smsRecords`，不会拿旧快照覆盖）。
     */
    fun loadMoreServerSms() {
        val current = _state.value
        if (current.smsLoading) return
        if (current.smsRecords.size >= current.smsTotal) return
        viewModelScope.launch { loadServerSmsNow(page = current.smsPage + 1, append = true) }
    }

    /** 挂起版本，理由同 [refreshQueueNow]：下拉刷新要等到这次请求真的回来。 */
    suspend fun loadServerSmsNow(page: Int = 1, append: Boolean = false) {
        _state.update { it.copy(smsLoading = true, smsError = null) }
        try {
            val response = RetrofitClient.getApiService()
                .mySms(page = page, pageSize = SMS_PAGE_SIZE, includeIgnored = true)

            val body = response.body()
            if (response.isSuccessful && body?.data != null) {
                _state.update { current ->
                    val merged = if (append) {
                        // 拼接前按 id 去重：翻页期间若有新短信进来，服务端的分页会整体
                        // 后移，第二页可能把第一页已经给过的记录再发一遍。不去重的话，
                        // 列表就出现两条同 id 的记录 —— 而 LazyColumn 的 key 正是 id，
                        // 撞 key 直接抛异常崩掉，不是视觉上的重复。
                        (current.smsRecords + body.data.records).distinctBy { it.id }
                    } else {
                        body.data.records
                    }
                    current.copy(
                        smsRecords = merged,
                        smsTotal = body.data.total,
                        smsPage = page,
                        smsLoading = false
                    )
                }
                // 列表刷新时同步刷新计数，保证两者永远一致
                refreshServerStats()
            } else {
                _state.update {
                    it.copy(
                        smsLoading = false,
                        smsError = "读取失败（HTTP ${response.code()}）" +
                            (parseErrorMessage(response.errorBody()?.string())?.let { m -> "：$m" }
                                ?: "")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Load server SMS failed", e)
            _state.update {
                it.copy(
                    smsLoading = false,
                    smsError = "连不上服务器：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    // ---------- 自检 ----------

    /**
     * 一键自检：把「为什么收不到/传不上」的常见原因按顺序查一遍。
     * 每一项都独立判定，互不短路，好让用户一次看到全部问题。
     */
    fun runSelfTest() {
        if (_state.value.selfTestRunning) return
        _state.update { it.copy(selfTestRunning = true, selfTest = emptyList()) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            val results = mutableListOf<SelfTestItem>()

            fun add(label: String, ok: Boolean, detail: String) {
                results += SelfTestItem(label, ok, detail)
                _state.update { it.copy(selfTest = results.toList()) }
            }

            add(
                "短信接收权限",
                hasPermission(app, Manifest.permission.RECEIVE_SMS),
                if (hasPermission(app, Manifest.permission.RECEIVE_SMS)) "已授予" else "未授予，收不到任何短信"
            )
            // 这里原本还有一行「短信读取权限」（READ_SMS）。那个权限已经移除 ——
            // 本应用只从 SMS_RECEIVED 广播取消息，从没读过系统短信库，
            // 显示一个用不到的权限只会把人引去授权一个无关的东西。

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ok = hasPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                add("通知权限", ok, if (ok) "已授予" else "未授予，前台服务通知会被隐藏")
            }

            val ignoring = isIgnoringBatteryOptimizations(app)
            add(
                "电池优化白名单",
                ignoring,
                if (ignoring) "已加入，后台服务不易被杀" else "未加入，系统可能随时杀掉后台服务"
            )

            val registered = DevicePrefs.isRegistered(app)
            add(
                "设备注册",
                registered,
                if (registered) "已注册" else "未注册，请先注册设备"
            )

            if (registered) {
                // 探测结果自带「是否通过」。原先这里第二个参数写死 true，于是 404 也报通过。
                val (serverOk, serverDetail) = describeProbe(
                    RetrofitClient.probe(DevicePrefs.serverUrl(app))
                )
                add("服务器连通", serverOk, serverDetail)

                val heartbeatOk = HeartbeatSender.send(app)
                add(
                    "令牌有效",
                    heartbeatOk,
                    if (heartbeatOk) "心跳成功" else "心跳被拒绝，可能令牌失效或服务器不可达"
                )
            }

            // 结论留一份给主页那行「自检」：不落盘的话它每次都显示「还没跑过」，
            // 那行就退化成纯按钮，起不到「不用点进去就知道六项还过不过」的作用。
            val failed = results.count { !it.ok }
            val summary = if (failed == 0) "${results.size} 项全部通过" else "$failed 项未通过"
            val at = System.currentTimeMillis()
            DevicePrefs.setLastSelfTest(app, summary, at)
            _state.update {
                it.copy(selfTestRunning = false, lastSelfTest = summary, lastSelfTestAt = at)
            }
        }
    }

    /**
     * 一次性心跳，供被禁用横幅上的「检查状态」使用。
     *
     * 这是必需的逃生口：禁用 + 服务已停止时，若服务起不来就没人在轮询，
     * 设备永远学不到自己已被恢复。
     */
    fun checkStatusNow() {
        viewModelScope.launch {
            _state.update { it.copy(testResult = "检查中…") }
            val ok = HeartbeatSender.send(getApplication())
            _state.update {
                it.copy(
                    testResult = when {
                        !ok -> "检查失败：心跳未成功，请确认服务已启动且网络可达"
                        DeviceStatus.isDisabled(getApplication()) -> "仍处于禁用状态"
                        else -> "已恢复"
                    }
                )
            }
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ---------- 服务开关 ----------

    fun toggleService() {
        // 读持久化的那份而不是界面状态：它没有推送延迟，也不会被别的写入覆盖。
        // 服务其实没起来时（例如刚被系统杀掉），这里读到 false 会去把它拉起来，
        // 而不是对着一个不存在的服务调 stopService 后界面毫无反应。
        if (GatewayState.isRunning(getApplication())) {
            GatewayForegroundService.stop(getApplication())
            reportOfflineToServer()
        } else {
            startService()
        }
    }

    /**
     * 告诉服务端「本机网关已停止」。
     *
     * 不这么做的话，停止之后管理后台还要继续显示在线 90 秒（判离线靠心跳超时），
     * 现场看到的是「我明明停了，它还绿着」。
     *
     * 发不出去也无所谓：服务端同样有心跳超时兜底，这里只是让状态**立刻**对上。
     * 因此失败只记日志，不提示用户 —— 网关确实已经停了，拿一个网络问题去打扰他没有意义。
     */
    private fun reportOfflineToServer() {
        val app = getApplication<Application>()
        if (!DevicePrefs.isRegistered(app)) return

        viewModelScope.launch {
            try {
                RetrofitClient.ensureConfigured(app)
                RetrofitClient.getApiService().reportOffline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Report offline failed", e)
            }
        }
    }

    /**
     * 未注册时不启服务：否则只会拉起一个每 30 秒刷「设备未注册」通知的空转服务。
     *
     * 但**被禁用时允许启动** —— 服务的心跳是设备唯一能发现自己被恢复的通道，
     * 禁用它反而会造出「不可启动 → 不轮询 → 永远学不到已恢复」的死锁。
     */
    private fun startService() {
        val app = getApplication<Application>()
        if (!DevicePrefs.isRegistered(app)) return
        GatewayForegroundService.start(app)
    }
}
