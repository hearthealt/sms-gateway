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
        val app = context.applicationContext

        // 先水合再比较。不水合的话 _disabled 还是字段初始值 false，任何一次
        // set(false) 都会被当成「从禁用恢复」，日志里凭空多出一条。
        ensureLoaded(app)

        val changed = _disabled.value != disabled
        DevicePrefs.setDisabled(app, disabled)
        hydrated = true
        if (changed) {
            _disabled.value = disabled

            // 只在**跃迁**时记。心跳每次收到服务端状态都会调它，稳态下每次都记会把表写爆，
            // 而「一直是启用」这件事没有任何信息量。
            EventLog.write(
                app,
                if (disabled) EventLog.DEVICE_DISABLED else EventLog.DEVICE_ENABLED,
                if (disabled) EventLog.LEVEL_WARN else EventLog.LEVEL_INFO,
                reason = if (disabled) "被管理员禁用" else "已恢复"
            )
        }
    }

    /** 当前值（同步读取，供没有协程上下文的地方用，如 Worker 的守卫判断）。 */
    fun isDisabled(context: Context): Boolean {
        ensureLoaded(context)
        return _disabled.value
    }
}
