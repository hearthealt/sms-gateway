package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smsgateway.app.service.GatewayForegroundService
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.GatewayState

/**
 * 在开机、以及**本应用被覆盖安装之后**重新拉起网关前台服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            // 覆盖安装 APK 会把进程连同前台服务一起杀掉，而开机广播不会补发 ——
            // 少了这一条，给设备推一次版本更新就等于让整个车队的网关停摆，
            // 且不会自愈：服务只有「注册成功」「用户手动点启动」「开机」三个入口，
            // 无人值守的设备会一直静默失联。MY_PACKAGE_REPLACED 正是官方为这个场景
            // 提供的入口，它在后台启动前台服务的豁免名单上。
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 两个条件都必须看，缺一个就会违背界面上的承诺。
                //
                // **未注册**时不要拉起服务：否则开机会挂上一条常驻的「设备未注册」通知，
                // 而它什么也做不了。注册成功后 registerDevice() 本来就会启动服务，不会漏。
                //
                // **用户手动停过**时也不能拉起来：停止按钮在界面上写的是「短信会留在本地，
                // 不会上报」，而服务起来之后 onCreate 会 markStarted（运行态写回 true）
                // 并立刻排一次上传 —— 积压的短信会被全部上报。原先这里只看注册状态，
                // 于是「点停止 → 手机重启」或「点停止 → 推一次 APK 更新」就等于把那个
                // 承诺悄悄撤销了，而用户什么都没做。
                //
                // gateway_running 在关机时不会被清掉（关机不走 onDestroy），所以
                // MY_PACKAGE_REPLACED 那条路上它也是可信的：停过就是 false，没停过就是 true。
                // 判据与 DashboardViewModel.ensureGatewayServiceRunning 完全一致 ——
                // 两处标准不同正是这个 bug 的成因。
                if (DevicePrefs.isRegistered(context) && GatewayState.isRunning(context)) {
                    startGateway(context)
                }
            }
        }
    }

    /**
     * 启动前台服务，并兜住「从后台启动前台服务」被系统拒绝的情况。
     *
     * 上面三个 action 里只有 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED 在官方豁免名单上，
     * QUICKBOOT_POWERON **不在**（它不是受保护广播，部分厂商 ROM 开机时只发它）。
     * 此时 startForegroundService 会抛 ForegroundServiceStartNotAllowedException，
     * 而这个广播接收器是 exported 且没有权限保护的 —— 任何第三方应用都能发这个
     * action 把它打崩，用户看到的是「云驿站已停止运行」。
     *
     * 挡住它的代价只是「这一次没起来」（还有 BOOT_COMPLETED 与用户手动开 App 兜底），
     * 比崩溃好得多。
     */
    private fun startGateway(context: Context) {
        try {
            GatewayForegroundService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "拉起网关服务失败，可能是不在后台启动前台服务的豁免名单内", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
