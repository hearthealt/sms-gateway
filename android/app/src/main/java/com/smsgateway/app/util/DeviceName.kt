package com.smsgateway.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 设备名称：**跟随手机自己的名字**，本应用不再单独存一份。
 *
 * 此前这个名字由应用自己维护（设置页手填），于是它和手机里的名字、二维码里带来的
 * 名字三边打架：扫一张别人机器的码就会把**那台机器的名字**写进本机，两台手机顶着
 * 同一个名字出现在管理后台，现场根本分不出哪台是哪台。
 *
 * 现在只认手机，按这个顺序取：
 * 1. `Settings.Secure.bluetooth_name` —— 「设置 → 关于手机 → 设备名称」改的就是它
 *    （MIUI/HyperOS 上设备名与蓝牙名是同一个值），也是用户唯一会主动去改的那个；
 * 2. `Settings.Global.device_name` —— AOSP 的设备名，部分 ROM 上它才是权威值；
 * 3. 两者都读不到就回落 `厂商 + 机型`。
 *
 * 读系统设置不需要任何权限，但系统值确实可能为空（恢复出厂、部分 ROM 压根不写），
 * 所以三层兜底都要有 —— 上报给服务端的名字不能是空串。
 */
object DeviceName {

    private const val KEY_BLUETOOTH_NAME = "bluetooth_name"
    private const val KEY_DEVICE_NAME = "device_name"

    fun read(context: Context): String {
        readSetting(context, secure = true, KEY_BLUETOOTH_NAME)?.let { return it }
        readSetting(context, secure = false, KEY_DEVICE_NAME)?.let { return it }
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    /**
     * 取值失败一律当没有：某些 ROM 会在这里抛 SecurityException，
     * 一个名字不值得让心跳或注册整条挂掉。
     */
    private fun readSetting(context: Context, secure: Boolean, key: String): String? =
        runCatching {
            if (secure) {
                Settings.Secure.getString(context.contentResolver, key)
            } else {
                Settings.Global.getString(context.contentResolver, key)
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
