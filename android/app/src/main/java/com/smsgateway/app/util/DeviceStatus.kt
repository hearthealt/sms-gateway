package com.smsgateway.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备的「已被管理员禁用」状态。
 *
 * 之所以单独成一个对象而不是塞进 DashboardState：Worker 和前台服务也要读它，
 * 而 WorkManager 拉起进程时 DashboardViewModel 根本不会被创建。
 *
 * 真源仍是 SharedPreferences（见 DevicePrefs.isDisabled）——它跨进程死亡存活，
 * 也是唯一四处组件都能读到的地方。这里的 StateFlow 只是给界面用的推送通道，
 * 写法与 [GatewayState] 完全一致：prefs 当真源、StateFlow 当界面推送通道。
 */
object DeviceStatus {

    private val _disabled = MutableStateFlow(false)
    val disabled: StateFlow<Boolean> = _disabled.asStateFlow()

    @Volatile
    private var hydrated = false

    /** 首次访问时从 prefs 水合。幂等，可从任意线程调用。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (hydrated) return
        _disabled.value = DevicePrefs.isDisabled(context)
        hydrated = true
    }

    /** 写入状态，同时回写 prefs。 */
    fun set(context: Context, disabled: Boolean) {
        DevicePrefs.setDisabled(context.applicationContext, disabled)
        hydrated = true
        if (_disabled.value != disabled) {
            _disabled.value = disabled
        }
    }

    /** 当前值（同步读取，供没有协程上下文的地方用，如 Worker 的守卫判断）。 */
    fun isDisabled(context: Context): Boolean {
        ensureLoaded(context)
        return _disabled.value
    }
}
