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
import com.smsgateway.app.database.EventLogEntity
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
import com.smsgateway.app.util.EventLog
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
     * 重要日志页（本地事件记录，保留 7 天）。
     *
     * 与 [queue] 一样只存最近若干条：这张表理论上无界，而界面一次渲染几万行没有意义。
     */
    val eventLog: List<EventLogEntity> = emptyList(),
    val eventLogLoading: Boolean = false,
    /** 事件表里的总条数（不受上面那个展示上限影响），用来显示「共 N 条」。 */
    val eventLogTotal: Int = 0,

    /**
     * 主页两张小图的数据（近 7 天 + 今日逐小时）；还没取到时为 null。
     *
     * null 与「取到但是空的」要分开：前者是「还没问过服务端」，界面据此摆占位骨架；
     * 后者是「服务端说这七天一条都没有」，那是有信息量的，该把空图摆出来。
     */
    val trend: DeviceTrend? = null,

    /**
     * 本进程内是否**已经问过一次**趋势数据（成功或失败都算）。
     *
     * [trend] 为 null 时，界面要能区分「正在读」和「读了但没拿到」—— 只靠 null
     * 分不出来，于是一次网络失败之后占位骨架会永远写着「读取中」，
     * 那是在骗人。与 [smsLoaded] 同一个理由、同一套写法。
     *
     * 未注册时**不置位**：那种情况问都不问（见 refreshTrend 的提前返回），
     * 主页那时显示的是「设备未注册」，不该多摆一块「读不到趋势」。
     */
    val trendAttempted: Boolean = false,

    // 服务端记录页
    val smsRecords: List<SmsRecord> = emptyList(),
    val smsTotal: Long = 0,
    /** 当前已加载到第几页（从 1 开始）。「加载更多」读它 +1。 */
    val smsPage: Int = 1,
    val smsLoading: Boolean = false,
    val smsError: String? = null,
    /**
     * 本进程内是否**已经完成过一次**服务端记录加载（成功或失败都算）。
     *
     * 与 [smsLoading] 配合，区分「首次进入」与「复访刷新」：ViewModel 是 Activity 级的，
     * [smsRecords] 在页面之间一直留着，所以复访时列表非空 —— 整页转圈会把它闪没，
     * 而用户看到的那片「什么都没发生」正是「以为没在刷新」的由来。复访时改成顶部
     * 一条细进度条，不遮内容、不跳布局。
     *
     * 失败也算「读完一次」：否则一次网络失败之后，复访会永远停在整页转圈上。
     */
    val smsLoaded: Boolean = false,

    // 自检页
    val selfTest: List<SelfTestItem> = emptyList(),
    val selfTestRunning: Boolean = false,

    /**
     * 转发链路测试：正在测 / 逐个渠道的结果 / 整条请求失败的原因。
     *
     * 三者并存而不是合成一个：一次测试里「请求本身失败」（服务器不可达）与
     * 「请求成功但某个渠道发不出去」是两回事，前者该整块报错，后者要逐条列出来。
     * [notifyTestResults] 为 null 表示还没测过。
     */
    val notifyTesting: Boolean = false,
    val notifyTestResults: List<NotifyTestResult>? = null,
    val notifyTestError: String? = null,

    /**
     * 当前启用的转发渠道名；null = 还没问过。
     *
     * 用来在按钮旁边先摆出「会发给谁」：一个渠道都没启用时，点下去只会返回空列表，
     * 而人看到的是「测过了，什么都没发生」—— 那比什么都不知道更糟。
     */
    val notifyChannels: List<String>? = null
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

        /**
         * 重要日志页一次读多少条。
         *
         * 保留 7 天的事件在几百条量级，500 条足够覆盖「昨天出的问题今天来查」；
         * 而一次渲染上万行既没意义、又会让 Compose 的列表初始化变慢。
         */
        private const val EVENT_LOG_PAGE_SIZE = 500

        /** 趋势图看几天。 */
        private const val TREND_DAYS = 7

        /** 趋势图多久刷一次（单位是 5 秒的轮询 tick，60 × 5s = 5 分钟）。 */
        private const val TREND_REFRESH_TICKS = 60
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
                // 刚传上去一条 —— 趋势图立刻跟上，不等下一个周期
                refreshTrend()
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
                isDisabled = DevicePrefs.isDisabled(app)
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

                }
                // 趋势图 5 分钟一次就够：按天/按小时聚合的数字，30 秒刷一遍没有新信息。
                // 刚传上去一条时会单独刷一次（见 observeUploads），所以「今天 +1」也不会迟到。
                //
                // 但**一次都没拿到过**时降到 30 秒一次：那时主页摆的是「暂时读不到」的占位，
                // 让它挂满 5 分钟太久了 —— 用户会当成新出的毛病。拿到数据后自动回到 5 分钟。
                val trendMissing = _state.value.trend == null
                if (ticks % TREND_REFRESH_TICKS == 0 || (trendMissing && ticks % 6 == 0)) {
                    refreshTrend()
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
        EventLog.write(
            getApplication(), EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR,
            reason = "连不上服务器"
        )
        ConnectOutcome(false, "连不上服务器，请到设置里检查服务器地址")
    } catch (e: Exception) {
        Log.e(TAG, "Registration failed", e)
        EventLog.write(
            getApplication(), EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR,
            reason = e.javaClass.simpleName
        )
        ConnectOutcome(false, "注册失败：${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 真正发注册请求。
     *
     * **刻意不写 registerMessage**：由调用方决定这条结论走哪条通道（理由见 [runRegistration]）。
     */
    private suspend fun performRegistration(): ConnectOutcome {
        val app = getApplication<Application>()

        // 「首次注册」与「重新注册」在日志里长得一模一样，而设备身份被重置过一次
        // 恰恰是排查「服务端怎么多出一台设备」的关键线索。必须在下面 updateToken
        // 之前读，那之后 token 已经换成新的了。
        val isReRegister = DevicePrefs.deviceToken(app).isNotBlank()

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
            EventLog.write(
                app, EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR,
                reason = "HTTP ${response.code()}"
            )
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

        EventLog.write(
            app, EventLog.DEVICE_REGISTERED, EventLog.LEVEL_INFO,
            reason = if (isReRegister) "重新注册" else "首次注册"
        )

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

    // ---------- 重要日志页 ----------

    fun refreshEventLog() {
        viewModelScope.launch { refreshEventLogNow() }
    }

    /**
     * 事件日志读取的挂起版本。写法与 [refreshQueueNow] 一致：
     * 真正干活的是这个，非挂起版只是把活派给 viewModelScope 的一层壳。
     */
    suspend fun refreshEventLogNow() {
        _state.update { it.copy(eventLogLoading = true) }
        try {
            val dao = database.eventLogDao()
            val rows = dao.getRecent(EVENT_LOG_PAGE_SIZE)
            // 总数单独查：展示有上限（最近 500 条），而「共 N 条」要如实反映库里到底有多少，
            // 否则超过 500 条之后那个数字会永远停在 500，看着像被截断了。
            val total = dao.count()
            _state.update {
                it.copy(eventLog = rows, eventLogTotal = total, eventLogLoading = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load event log failed", e)
            _state.update { it.copy(eventLogLoading = false) }
        }
    }

    /** 清空重要日志。结果走 [DashboardState.settingsMessage] 那条一次性通道（同 [clearUploadedRecords]）。 */
    fun clearEventLog() {
        viewModelScope.launch {
            try {
                val removed = database.eventLogDao().deleteAll()
                Log.i(TAG, "Cleared $removed event log rows")
                _state.update { it.copy(settingsMessage = "已清理 $removed 条日志") }
            } catch (e: Exception) {
                Log.e(TAG, "Clear event log failed", e)
                _state.update {
                    it.copy(settingsMessage = "清理失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------- 主页趋势图 ----------

    /**
     * 拉近 7 天 + 今日逐小时的聚合。
     *
     * 失败时**保留上一次的结果**，不清空、也不报错：主页是「一眼确认它在不在干活」的地方，
     * 为一次请求失败把两张图变没，比图稍旧几分钟糟糕得多。真要排查有记录页。
     */
    private suspend fun refreshTrend() {
        // 未注册时也要把 trendAttempted 置上。
        //
        // 这里原先想的是「未注册就别多摆一块读不到趋势」——**错的**：主页那张卡现在
        // 是无条件渲染占位骨架的（见 TrendCard），不置位的结果是骨架永远写着「读取中」，
        // 正好成了注释里反复说要避免的那句谎话。未注册的设备一进来就是这个样子。
        if (!DevicePrefs.isRegistered(getApplication())) {
            _state.update { it.copy(trendAttempted = true) }
            return
        }
        try {
            val response = RetrofitClient.getApiService().smsTrend(TREND_DAYS)
            val trend = response.body()?.data
            _state.update { it.copy(trend = trend ?: it.trend, trendAttempted = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Load trend failed", e)
            // 失败也要置位：否则占位骨架会一直写着「读取中」，而它其实已经不读了。
            // 保留上一次的数据（if (trend == null) 时不覆盖），与 refreshServerStats 同一个取舍。
            _state.update { it.copy(trendAttempted = true) }
        }
    }

    /**
     * 拉一次「当前有哪些启用的转发渠道」。进自检页时调，用来在按钮旁边说清会发给谁。
     *
     * 失败就静默留着上一次的结果：这只是个提示，为它报错不值当 —— 真要测，点按钮
     * 那一下会给出准确得多的结论。
     */
    fun refreshNotifyChannels() {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.getApiService().notifyChannels()
                val names = response.body()?.data ?: return@launch
                _state.update { it.copy(notifyChannels = names) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Load notify channels failed", e)
            }
        }
    }

    /**
     * 让服务端给每个启用的转发渠道各发一条测试消息。
     *
     * **这是这一整条链路里唯一能主动验证的一环**：手机照收、心跳照发、管理端设备列表上
     * 一切正常，而码再也送不到微信里 —— 转发断掉是静默的，之前没有任何界面能回答
     * 「码到底送出去了没有」。
     *
     * 服务端按设备限流（5 分钟一次），这里也挡一道连点：它真的会往外发消息。
     */
    fun testNotify() {
        if (_state.value.notifyTesting) return

        _state.update {
            it.copy(notifyTesting = true, notifyTestError = null)
        }

        viewModelScope.launch {
            try {
                val response = RetrofitClient.getApiService().testNotify()
                val body = response.body()
                if (response.isSuccessful && body?.data != null) {
                    _state.update {
                        it.copy(notifyTesting = false, notifyTestResults = body.data)
                    }
                } else {
                    // 与别的请求不同，这里的失败要**留在页面上**：限流提示、未配置渠道、
                    // 服务器不可达，三种都要求人做点什么，一闪而过的话等于没说
                    _state.update {
                        it.copy(
                            notifyTesting = false,
                            notifyTestError = parseErrorMessage(response.errorBody()?.string())
                                ?: "测试失败（HTTP ${response.code()}）"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notify test failed", e)
                _state.update {
                    it.copy(
                        notifyTesting = false,
                        notifyTestError = "连不上服务器：${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    // ---------- 主页「最近收到」 ----------

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

    /**
     * 用户正停在服务端记录页、而本机刚有一条短信上传成功：翻新第一页。
     *
     * 由页面订阅 [UploadEvents] 后调用（**不是**在 [startMonitoring] 的轮询里做）——
     * 这一页只在被看着的时候才需要实时，离开页面就不该再为它发请求。
     *
     * 本页只列**本设备**的记录（`mySms`），所以「本机上传成功」是唯一需要立刻反映的事件，
     * 不必为此加轮询。
     *
     * 守卫读 `_state.value` 而不是由页面传参：用户翻到第 2 页之后，新数据在第一页 ——
     * 直接重拉会把他正在看的位置顶掉。这不是丢数据，只是别把人的位置踢走。
     */
    fun onUploadedWhileViewingServerSms() {
        if (_state.value.smsPage > 1) return
        loadServerSms()
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
                        smsLoading = false,
                        smsLoaded = true
                    )
                }
                // 列表刷新时同步刷新计数，保证两者永远一致
                refreshServerStats()
            } else {
                _state.update {
                    it.copy(
                        smsLoading = false,
                        smsLoaded = true,
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
                    smsLoaded = true,
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

        // 顺手把「有哪些启用的转发渠道」也问一遍：自检页那块「测转发」要拿它
        // 在按钮旁边说清会发给谁（一个都没有时，点了也只会返回空列表）
        refreshNotifyChannels()

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

            _state.update { it.copy(selfTestRunning = false) }
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
