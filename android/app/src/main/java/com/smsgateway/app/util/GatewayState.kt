package com.smsgateway.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网关（前台服务）当前是不是在运行。
 *
 * 真源是 SharedPreferences（见 [DevicePrefs.isGatewayRunning]），这里的 StateFlow
 * 只是给界面用的推送通道 —— 与 [DeviceStatus] 同一套写法、同一个理由：
 * **Worker 和广播接收器也要读它，而 WorkManager 拉起进程时 DashboardViewModel
 * 根本不会被创建**，进程内的静态量在那条路径上恒为 false。
 *
 * 它要挡住的是这样一个洞：界面上「停止网关」明确承诺「短信会留在本地，不会上报」，
 * 但采集侧原先只看「已注册」「没被禁用」两条，于是停掉网关之后，短信照样入库、
 * WorkManager 照样把进程拉起来把 queue 传上去 —— 停止按钮等于没生效。
 */
object GatewayState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 本次启动的时刻，没在跑时为 null。
     *
     * 界面要拿它区分「刚起来，正在连」和「起来很久了，一次都没连上」——
     * 只看最近一次心跳成功的时间是分不出来的，那种情况下它两者都是 null。
     */
    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

    @Volatile
    private var hydrated = false

    /** 首次访问时从 prefs 水合。幂等，可从任意线程调用。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (hydrated) return
        _running.value = DevicePrefs.isGatewayRunning(context)
        _startedAt.value = DevicePrefs.gatewayStartedAt(context)
        hydrated = true
    }

    /** 写入状态，同时回写 prefs。 */
    fun set(context: Context, running: Boolean) {
        val app = context.applicationContext
        DevicePrefs.setGatewayRunning(app, running)
        // 停了就不再「启动过」。留着它，下一次启动到第一次心跳之间那段会被算成
        // 「已经启动很久却一直没连上」，平白报一次故障。
        if (!running) {
            DevicePrefs.setGatewayStartedAt(app, null)
            _startedAt.value = null
        }
        hydrated = true
        if (_running.value != running) {
            _running.value = running
        }
    }

    /**
     * 服务实例创建时记一笔「现在这条命是从什么时候开始的」，同时置运行态。
     *
     * **只在 onCreate 里调用**，不要放到 onStartCommand：那个回调每次收到启动命令
     * 都会来一遍（系统的 START_STICKY 重启、覆盖安装、以及应用每次打开时的对账补发），
     * 拿它当启动时刻，会把一个已经跑了三小时但一直没连上的设备重新算成「刚起来」，
     * 恰好抹掉这个字段存在的意义。
     */
    fun markStarted(context: Context, at: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        DevicePrefs.setGatewayStartedAt(app, at)
        _startedAt.value = at
        set(context, true)
    }

    /** 当前值（同步读取，供没有协程上下文的地方用，如 Worker / 广播接收器的守卫判断）。 */
    fun isRunning(context: Context): Boolean {
        ensureLoaded(context)
        return _running.value
    }
}
