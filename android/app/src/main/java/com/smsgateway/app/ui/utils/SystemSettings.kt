package com.smsgateway.app.ui.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 跳系统设置页的两条常用路径。
 *
 * 抽出来是因为有三处要用：权限横幅、电池优化横幅、以及锁屏上「忘记 PIN」那条
 * （它要引导用户去清应用数据）。三处各写一份 runCatching + fallback 只会漏改一处。
 *
 * 都带 fallback：部分 ROM 会把某个 action 掐掉（`startActivity` 抛
 * ActivityNotFoundException），那时退到应用详情页 —— 那里至少能手动改权限、清数据。
 */
object SystemSettings {

    /** 应用详情页：权限、通知、存储（清数据）、卸载都在这里。 */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 电池优化白名单。ROM 掐掉这个页面时退到应用详情页。 */
    fun openBatteryOptimization(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { openAppDetails(context) }
    }
}
