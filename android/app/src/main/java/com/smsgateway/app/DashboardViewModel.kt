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
import com.smsgateway.app.service.GatewayForegroundService
import com.smsgateway.app.util.AuthState
import com.smsgateway.app.util.DeviceName
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.HeartbeatSender
import com.smsgateway.app.util.UploadEvents
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 自检结果的一项。 */
data class SelfTestItem(val label: String, val ok: Boolean, val detail: String)

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
    val pendingCount: Int = 0,
    val todaySmsCount: Int = 0,
    val todayCodeCount: Int = 0,
    val isRegistering: Boolean = false,
    val registerMessage: String? = null,
    /**
     * 被禁用横幅上「检查状态」的结论。
     *
     * **只给那个横幅用。** 它是持久展示的：禁用状态下用户要拿它留在屏幕上对照，
     * 所以不该走一闪而过的通道。设置页原先也往这里写，结果是「地址格式不合法」
     * 一直挂在页面上擦不掉 —— 共享一个持久字段就会这样。
     */
    val testResult: String? = null,

    // 设置页的一次性提示
    /** 测试连接进行中。按钮据此切成「测试中…」并禁用。 */
    val isTestingConnection: Boolean = false,
    /**
     * 设置页的一次性提示：测试连接的结论、清理本地记录的结果等。
     * 设置页消费后立即清空，以 snackbar 一闪而过。
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

    // 服务端记录页
    val smsRecords: List<SmsRecord> = emptyList(),
    val smsTotal: Long = 0,
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
     * 探活请求的并发闸门。理由同上：连点会起出多个并发探测，谁先回来谁把
     * isTestingConnection 置为结束，于是按钮提前解禁、提示串台。
     *
     * 设置页也会在探测期间禁用按钮，但那是界面层的表达；真正防住重复的是这里 ——
     * 界面状态可能因为重组、返回再进入而落后半步。
     */
    private val testInFlight = AtomicBoolean(false)

    private val database = AppDatabase.getInstance(application)
    private val prefs = DevicePrefs.get(application)

    init {
        loadSavedState()
        restoreRetrofitConfig()
        tryAutoFillPhone()
        startMonitoring()
        observeService()
        observeUploads()
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
            HeartbeatSender.lastSuccessAt.collect { at ->
                _state.update { it.copy(lastHeartbeatAt = at) }
            }
        }
        viewModelScope.launch {
            DeviceStatus.disabled.collect { disabled ->
                _state.update { it.copy(isDisabled = disabled) }
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
                        registerMessage = "服务端已不认这台设备（令牌失效），请重新注册"
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
        _state.update {
            it.copy(
                deviceId = DevicePrefs.deviceId(app),
                deviceToken = DevicePrefs.deviceToken(app),
                phone = DevicePrefs.phone(app),
                serverUrl = DevicePrefs.serverUrl(app),
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
        }
        RetrofitClient.ensureConfigured(context)

        _state.update {
            it.copy(
                serverUrl = normalized,
                deviceToken = if (changed) "" else it.deviceToken,
                registerMessage = if (changed) {
                    "服务器地址已变更，请重新注册设备"
                } else {
                    it.registerMessage
                }
            )
        }
    }

    /** 供二维码导入使用：由界面确认后调用这里写入。@return 同 [updateServerUrl]。 */
    fun importServerUrl(url: String): Boolean = updateServerUrl(url)

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
        if (DevicePrefs.phone(app).isNotBlank()) return

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
                        enrollSecret = DevicePrefs.getOrCreateEnrollSecret(app)
                    )
                )

                val data = response.body()?.data
                if (response.isSuccessful && data != null) {
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

                    _state.update {
                        it.copy(
                            deviceToken = token,
                            phone = phone.orEmpty(),
                            // 成功也要说一声。原先这里置 null，于是点了「重新注册」之后
                            // 界面上什么都不变 —— 现场无从判断到底成没成，只能靠猜。
                            registerMessage = "注册成功"
                        )
                    }

                    SmsUploadWorker.enqueue(app)
                    refreshServerStats()
                    startService()
                } else {
                    // 这里原本读 body()?.message —— 但 Retrofit 在非 2xx 时 body() 恒为 null，
                    // 载荷其实在 errorBody() 里，不解析就永远只能显示一个光秃秃的状态码。
                    val detail = parseErrorMessage(response.errorBody()?.string())
                    _state.update {
                        it.copy(
                            registerMessage = "注册失败（HTTP ${response.code()}）" +
                                (detail?.let { "：$it" } ?: "")
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Registration failed: server unreachable", e)
                _state.update {
                    it.copy(registerMessage = "连不上服务器，请到设置里检查服务器地址")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed", e)
                _state.update {
                    it.copy(registerMessage = "注册失败：${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                registerInFlight.set(false)
                _state.update { it.copy(isRegistering = false) }
            }
        }
    }

    /** 从错误响应体里取出后端给的 message。解析失败就当没有，不因此再抛一次。 */
    private fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(raw, ErrorBody::class.java)?.message?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class ErrorBody(val code: Int = 0, val message: String? = null)

    /** 设置页的「测试连接」：测输入框里当前这个地址，不必先保存。 */
    fun testConnection(rawUrl: String) {
        if (!testInFlight.compareAndSet(false, true)) return

        val url = rawUrl.trim().ifBlank { DevicePrefs.DEFAULT_SERVER_URL }
        _state.update { it.copy(isTestingConnection = true) }

        viewModelScope.launch {
            try {
                // probe 不再抛异常，连不上也是 ProbeResult 的一种，所以无需再包 try/catch
                val (_, message) = describeProbe(RetrofitClient.probe(url))
                _state.update { it.copy(settingsMessage = message) }
            } finally {
                // 放在 finally：协程被取消时也要把闸门和「测试中」状态放掉，
                // 否则按钮会永远停在禁用态，用户只能杀掉应用。
                testInFlight.set(false)
                _state.update { it.copy(isTestingConnection = false) }
            }
        }
    }

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
        viewModelScope.launch {
            _state.update { it.copy(queueLoading = true) }
            try {
                val rows = database.smsQueueDao().getOutstanding()
                _state.update { it.copy(queue = rows, queueLoading = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Load queue failed", e)
                _state.update { it.copy(queueLoading = false) }
            }
        }
    }

    /** 手动重试单条：立刻可传并清零重试计数，然后唤醒 worker。 */
    fun retrySms(id: Long) {
        viewModelScope.launch {
            try {
                database.smsQueueDao().retryNow(id)
                SmsUploadWorker.enqueue(getApplication())
                refreshQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Retry SMS $id failed", e)
            }
        }
    }

    fun deleteSms(id: Long) {
        viewModelScope.launch {
            try {
                database.smsQueueDao().deleteById(id)
                refreshQueue()
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

    // ---------- 服务端记录页 ----------

    fun loadServerSms(page: Int = 1) {
        viewModelScope.launch {
            _state.update { it.copy(smsLoading = true, smsError = null) }
            try {
                val response = RetrofitClient.getApiService()
                    .mySms(page = page, pageSize = SMS_PAGE_SIZE, includeIgnored = true)

                val body = response.body()
                if (response.isSuccessful && body?.data != null) {
                    _state.update {
                        it.copy(
                            smsRecords = body.data.records,
                            smsTotal = body.data.total,
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
