package com.smsgateway.app.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * 设备本地存储的统一定义。
 * 前台服务、广播接收器、Worker 与 ViewModel 都要读这些值，抽出来避免几边各写一份键名而写岔。
 */
object DevicePrefs {

    const val NAME = "sms_gateway_prefs"

    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_TOKEN = "device_token"

    /**
     * 重注册密钥。首次注册时生成、只在本机保存，服务端存的是它的 SHA-256。
     *
     * 丢了它就只能请管理员在控制台签一张恢复码 —— 这是刻意的：原先服务端对已存在的
     * deviceId 会把令牌原样返还，等于「知道设备号就能冒充这台设备」，
     * 而设备号在管理后台和任何截图里都看得到。
     */
    const val KEY_ENROLL_SECRET = "enroll_secret"

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

    /** 历史上被大量设备共用的 ANDROID_ID 坏值，见 newDeviceId()。 */
    private const val LEGACY_BROKEN_ANDROID_ID = "9774d56d682e549c"

    /**
     * 取设备标识，没有就生成一个并**立即同步落盘**。
     *
     * 这里必须用 commit() 而非 apply()：apply 是异步写盘，进程若在刷写前被杀，
     * 刚生成的标识就没了，下次启动又会生成一个新的 —— 服务端于是多出一台「新设备」。
     * 全项目只此一处需要同步写。
     */
    fun getOrCreateDeviceId(context: Context): String {
        val existing = deviceId(context)
        if (existing.isNotBlank()) return existing

        val generated = newDeviceId(context)
        get(context).edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    /**
     * 生成新设备标识。锚点用 ANDROID_ID（SSAID），不用随机 UUID。
     *
     * 随机 UUID 存在应用私有目录里，而私有目录按**包名**分。于是改包名 —— 以及卸载重装、
     * 清除应用数据 —— 都等于换一台设备：服务端多出一条记录，历史短信留在旧记录名下。
     * 2026-09 把包名从 com.smsgateway.app 改成 com.yunyi.smshub 时踩过一次。
     *
     * SSAID 的算法是 HMAC-SHA256(每个用户一把的随机 userkey, 签名证书)，由系统在
     * SettingsProvider 里算好，**输入里没有包名**，所以上述操作都不会让它变。
     * 它只在换签名密钥（证书变了）和恢复出厂（userkey 重新随机）时才变。
     *
     * 前缀 android- 是刻意加的：老记录是裸 UUID，新记录带前缀，管理后台一眼能分清
     * 哪些设备还悬在旧机制上；将来若换成别的锚点（例如 Device Owner 下的硬件序列号），
     * 也只换前缀即可，格式位置先占住。
     */
    private fun newDeviceId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        // 部分 ROM 会返回 null；9774d56d682e549c 是历史上被大量设备共用的坏值，
        // 一旦落库会让那些设备在服务端互相顶替。撞上这两种情况宁可退回随机 UUID ——
        // 至少每台设备还是独立的，只是失去「改包名不变」这个好处。
        if (androidId.isNullOrBlank() || androidId == LEGACY_BROKEN_ANDROID_ID) {
            return UUID.randomUUID().toString()
        }
        return "android-$androidId"
    }

    /** 重注册密钥，未生成时为空串。 */
    fun enrollSecret(context: Context): String =
        get(context).getString(KEY_ENROLL_SECRET, "").orEmpty()

    /**
     * 取重注册密钥，没有就生成一个并**立即同步落盘**。
     *
     * 与设备标识同理用 commit()：这个值一旦没落盘就丢了，而丢了它只能去控制台
     * 签恢复码 —— 不能因为进程在刷写完成前被杀就让现场多跑一趟。
     */
    fun getOrCreateEnrollSecret(context: Context): String {
        val existing = enrollSecret(context)
        if (existing.isNotBlank()) return existing

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        get(context).edit().putString(KEY_ENROLL_SECRET, generated).commit()
        return generated
    }

    /**
     * 采用二维码带来的设备身份。由管理员在控制台签发恢复码，设备扫码后调用。
     *
     * 同时清掉令牌：令牌是签发在**旧**身份上的，留着会让 isRegistered 仍为真，
     * 于是 App 拿着旧令牌去请求新身份的数据、一路 401。
     */
    fun adoptEnrollIdentity(context: Context, deviceId: String, secret: String) {
        get(context).edit()
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_ENROLL_SECRET, secret.trim())
            .remove(KEY_DEVICE_TOKEN)
            .commit()
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
