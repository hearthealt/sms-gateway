package com.smsgateway.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * 设备遥测读取。
 *
 * 心跳上报的电量 / 网络 / 充电状态此前是写死的常量（battery = 80、network = "wifi"），
 * 这里改为读真实值。读不到时返回 null，让字段在 JSON 中整体缺省，
 * 后端会保留上一次的有效值，而不是用假数据覆盖。
 */
object DeviceTelemetry {

    /**
     * 电量百分比（0-100），读不到返回 null。
     *
     * 用 BatteryManager 属性查询而非 ACTION_BATTERY_CHANGED 粘性广播：
     * targetSdk 34 下 registerReceiver 需要显式导出标志，属性查询没有这层麻烦。
     */
    fun batteryLevel(context: Context): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    /** 是否正在充电，读不到返回 null。 */
    fun isCharging(context: Context): Boolean? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        if (status == Int.MIN_VALUE) return null
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * 网络类型：wifi / cellular / ethernet / none / unknown，读不到返回 null。
     *
     * 不细分 4G/5G —— 那需要 READ_PHONE_STATE 权限，而本应用没有申请。
     */
    fun networkType(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return "none"
        val caps = manager.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }
}
