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

    @Volatile
    private var hydrated = false

    /** 首次访问时从 prefs 水合。幂等，可从任意线程调用。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (hydrated) return
        _running.value = DevicePrefs.isGatewayRunning(context)
        hydrated = true
    }

    /** 写入状态，同时回写 prefs。 */
    fun set(context: Context, running: Boolean) {
        DevicePrefs.setGatewayRunning(context.applicationContext, running)
        hydrated = true
        if (_running.value != running) {
            _running.value = running
        }
    }

    /** 当前值（同步读取，供没有协程上下文的地方用，如 Worker / 广播接收器的守卫判断）。 */
    fun isRunning(context: Context): Boolean {
        ensureLoaded(context)
        return _running.value
    }
}
