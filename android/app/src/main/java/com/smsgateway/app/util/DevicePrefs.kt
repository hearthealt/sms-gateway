package com.smsgateway.app.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 设备本地存储的统一定义。
 * 前台服务、广播接收器、Worker 与 ViewModel 都要读这些值，抽出来避免几边各写一份键名而写岔。
 */
object DevicePrefs {

    const val NAME = "sms_gateway_prefs"

    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_TOKEN = "device_token"
    const val KEY_PHONE = "phone"

    /**
     * 已保存号码属于哪张 SIM 卡（subscriptionId）。-1 表示未知（手动填写）。
     *
     * 存它是为了判断「这条短信来自另一张卡」：那张卡读不到号码时，
     * 宁可把号码留空，也不能拿这张卡的号码顶上。
     */
    const val KEY_PHONE_SUB_ID = "phone_sub_id"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_DEVICE_NAME = "device_name"

    /** 服务端把本设备置为 DISABLED 后缓存在本地，供 Worker/服务在无网时也能立刻停手。 */
    const val KEY_DEVICE_DISABLED = "device_disabled"

    /** 一次性修复标记：早期版本把上传失败的行错标成 failed，需要扫回 pending 一次。 */
    const val KEY_STRANDED_SWEPT = "stranded_rows_swept"

    /** 模拟器访问宿主机 localhost 的地址。 */
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        get(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    /** 设备标识。未注册时为空串，展示用的「未设置」由界面层格式化。 */
    fun deviceId(context: Context): String =
        get(context).getString(KEY_DEVICE_ID, "").orEmpty()

    fun deviceToken(context: Context): String =
        get(context).getString(KEY_DEVICE_TOKEN, "").orEmpty()

    fun phone(context: Context): String =
        get(context).getString(KEY_PHONE, "").orEmpty()

    fun phoneSubId(context: Context): Int =
        get(context).getInt(KEY_PHONE_SUB_ID, -1)

    /**
     * @param subId 号码来源的卡；手动填写传 -1（未知）。
     */
    fun setPhone(context: Context, number: String, subId: Int) {
        get(context).edit()
            .putString(KEY_PHONE, number.trim())
            .putInt(KEY_PHONE_SUB_ID, subId)
            .apply()
    }

    /** 用户自定义的设备名。空串表示未设置，此时回落为「厂商 + 机型」。 */
    fun deviceName(context: Context): String =
        get(context).getString(KEY_DEVICE_NAME, "").orEmpty()

    fun setDeviceName(context: Context, name: String) =
        get(context).edit().putString(KEY_DEVICE_NAME, name.trim()).apply()

    fun isDisabled(context: Context): Boolean =
        get(context).getBoolean(KEY_DEVICE_DISABLED, false)

    fun setDisabled(context: Context, disabled: Boolean) =
        get(context).edit().putBoolean(KEY_DEVICE_DISABLED, disabled).apply()

    /**
     * 唯一权威的「已注册」判定：设备标识与令牌都非空。
     *
     * 界面门禁、BootReceiver、SmsReceiver、SmsUploadWorker 共用这一个定义，
     * 免得四边各写各的判断而互相矛盾。
     */
    fun isRegistered(context: Context): Boolean =
        deviceId(context).isNotBlank() && deviceToken(context).isNotBlank()

    /**
     * 取设备标识，没有就生成一个 UUID 并**立即同步落盘**。
     *
     * 这里必须用 commit() 而非 apply()：apply 是异步写盘，进程若在刷写前被杀，
     * 刚生成的 UUID 就没了，下次启动又会生成一个新的 —— 服务端于是多出一台「新设备」。
     * 全项目只此一处需要同步写。
     */
    fun getOrCreateDeviceId(context: Context): String {
        val existing = deviceId(context)
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        get(context).edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    /**
     * 清掉令牌但保留设备标识。
     *
     * 切换服务器时必须调用：令牌是在**旧服务器**上签发的，留着它会让 isRegistered 仍为 true，
     * App 于是启动网关、对着新服务器一直 401。清掉后强制重新注册。
     * 设备标识保留，所以重新注册仍是同一台设备，后台不会多出一条。
     */
    fun clearToken(context: Context) =
        get(context).edit().remove(KEY_DEVICE_TOKEN).apply()

    fun hasSweptStrandedRows(context: Context): Boolean =
        get(context).getBoolean(KEY_STRANDED_SWEPT, false)

    fun markStrandedRowsSwept(context: Context) =
        get(context).edit().putBoolean(KEY_STRANDED_SWEPT, true).apply()
}
