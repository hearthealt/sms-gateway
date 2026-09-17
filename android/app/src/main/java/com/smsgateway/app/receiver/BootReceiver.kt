package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsgateway.app.service.GatewayForegroundService
import com.smsgateway.app.util.DevicePrefs

/**
 * Boot completed receiver: restart the foreground service after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 未注册时不要拉起服务：否则开机会挂上一条常驻的「设备未注册」通知，
                // 而它什么也做不了。注册成功后 registerDevice() 本来就会启动服务，不会漏。
                if (DevicePrefs.isRegistered(context)) {
                    GatewayForegroundService.start(context)
                }
            }
        }
    }
}
