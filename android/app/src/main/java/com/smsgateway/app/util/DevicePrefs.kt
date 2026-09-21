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

    /**
     * 服务器接入口令，随管理后台「快速连接」的二维码下发，首次注册时带给服务端。
     *
     * 与 [KEY_ENROLL_SECRET] 是**两回事**，别混：那个证明「我是这台设备」（本机生成、
     * 服务端存哈希、重装丢失就得签恢复码）；这个证明「我被允许接入本服务器」
     * （管理员生成、明文可回读、跟着服务器走）。
     *
     * 因为它认的是**某一台服务器**，换地址时必须一起清掉（见 [clearEnrollToken]）——
     * 把甲服务器的口令带给乙服务器既没用，又是一次不必要的泄露。
     */
    const val KEY_ENROLL_TOKEN = "enroll_token"

    const val KEY_PHONE = "phone"

    /**
     * 已保存号码属于哪张 SIM 卡（subscriptionId）。-1 表示未知（手动填写）。
     *
     * 存它是为了判断「这条短信来自另一张卡」：那张卡读不到号码时，
     * 宁可把号码留空，也不能拿这张卡的号码顶上。
     */
    const val KEY_PHONE_SUB_ID = "phone_sub_id"
    const val KEY_SERVER_URL = "server_url"

    /** 服务端把本设备置为 DISABLED 后缓存在本地，供 Worker/服务在无网时也能立刻停手。 */
    const val KEY_DEVICE_DISABLED = "device_disabled"

    /**
     * 网关（前台服务）是否在运行。
     *
     * 落盘而不是只放进程内静态量：Worker 与短信广播接收器都可能在**进程刚被拉起**时
     * 判断这件事，那时内存里什么都还没有。见 [GatewayState]。
     */
    const val KEY_GATEWAY_RUNNING = "gateway_running"

    /**
     * 最近一次心跳成功的时间（epoch 毫秒）。
     *
     * 落盘的理由和 [KEY_GATEWAY_RUNNING] 一样：它原本只活在 [HeartbeatSender] 的内存态里，
     * 进程一被系统杀掉就归零。于是重新打开 App 时界面会显示「服务刚起来，正在连服务器」——
     * 哪怕这台设备已经跑了三天、只是刚才被杀了一次，也看不出「到底是刚启动，还是已经断了」。
     * 落盘之后界面能如实说「最后心跳 12 分钟前」。
     */
    const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"

    /**
     * 当前这一次网关启动的时刻（epoch 毫秒）。
     *
     * 它补的是 [KEY_LAST_HEARTBEAT_AT] 补不上的那一半：心跳时间只回答「上次成功是什么
     * 时候」，回答不了「一次都没成功过」。而后者必须再拿「这次启动到现在过了多久」去比，
     * 才能分出是「刚起来，正在连」还是「压根连不上」—— 少了它，一台从启动就没信号的
     * 设备会永远显示「服务刚起来」，哪怕它已经这样挂了三天。
     */
    const val KEY_GATEWAY_STARTED_AT = "gateway_started_at"

    /** 一次性修复标记：早期版本把上传失败的行错标成 failed，需要扫回 pending 一次。 */
    const val KEY_STRANDED_SWEPT = "stranded_rows_swept"

    /** 模拟器访问宿主机 localhost 的地址。 */
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        get(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    /**
     * 设备标识。
     *
     * **第一次打开应用时就已经生成**（见 [getOrCreateDeviceId]），正常装机不会是空串 ——
     * 空只出现在「升级前装的老版本、之后还没启动过」这一种情况，所以不要拿它空不空
     * 当「有没有注册」的判据：那个是 [isRegistered]，它还要看令牌。
     */
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

    // 设备名称不在这里存：它跟随手机本身，见 [DeviceName]。
    // 曾经在这里存过一份（设置页可手填），结果是扫码能把别人机器的名字写进来，
    // 两台手机在管理后台同名。

    fun isDisabled(context: Context): Boolean =
        get(context).getBoolean(KEY_DEVICE_DISABLED, false)

    /**
     * 写禁用状态。**值没变就不落盘** —— 调用方是心跳，每 30 秒一次，
     * 无条件写的话 7×24 运行下每天有近三千次无变化的整文件写入，纯耗电。
     */
    fun setDisabled(context: Context, disabled: Boolean) {
        if (isDisabled(context) == disabled) return
        get(context).edit().putBoolean(KEY_DEVICE_DISABLED, disabled).apply()
    }

    /** 网关是否在运行。未设置时按「没在跑」处理 —— 不确定就不上报，宁可让用户点一次启动。 */
    fun isGatewayRunning(context: Context): Boolean =
        get(context).getBoolean(KEY_GATEWAY_RUNNING, false)

    /** 写网关运行态。值没变就不落盘，理由同 [setDisabled]。 */
    fun setGatewayRunning(context: Context, running: Boolean) {
        if (isGatewayRunning(context) == running) return
        get(context).edit().putBoolean(KEY_GATEWAY_RUNNING, running).apply()
    }

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
     * 心跳时间戳最短落盘间隔。
     *
     * 刻意明显小于 90 秒的判离线阈值（服务端和界面用的是同一个值）：最坏情况下盘上的
     * 值比真值落后这么多，重启后界面先读到的就是旧值 —— 若这个间隔逼近 90 秒，
     * 服务刚起来、第一次心跳还没回来的那一瞬就会被误报成「连接中断」。
     */
    private const val HEARTBEAT_PERSIST_MIN_INTERVAL_MS = 60_000L

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

    /** 服务器接入口令，未设置时为空串（服务端未启用准入校验时就是这种状态）。 */
    fun enrollToken(context: Context): String =
        get(context).getString(KEY_ENROLL_TOKEN, "").orEmpty()

    /** 写入接入口令。空串等同于清除 —— 免得留一个空值在 prefs 里让判断多一种情况。 */
    fun setEnrollToken(context: Context, token: String) {
        val trimmed = token.trim()
        val editor = get(context).edit()
        if (trimmed.isEmpty()) editor.remove(KEY_ENROLL_TOKEN) else editor.putString(KEY_ENROLL_TOKEN, trimmed)
        editor.apply()
    }

    /**
     * 清掉接入口令。换服务器时必须调用 —— 口令是**某台服务器**签发的。
     */
    fun clearEnrollToken(context: Context) =
        get(context).edit().remove(KEY_ENROLL_TOKEN).apply()

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

    /** 最近一次心跳成功的时间，从未成功过返回 null。 */
    fun lastHeartbeatAt(context: Context): Long? =
        get(context).getLong(KEY_LAST_HEARTBEAT_AT, 0L).takeIf { it > 0L }

    /**
     * 记一次心跳成功。
     *
     * 用 apply() 而非 commit()：这个值每 30 秒写一次，丢一次的代价只是界面上少 30 秒
     * 的精度，不值得同步落盘卡住心跳那一步。
     *
     * 并且 [HEARTBEAT_PERSIST_MIN_INTERVAL_MS] 内不重复落盘 —— 界面读它只为显示
     * 「最后心跳 12 分钟前」这种分钟级的相对时间，30 秒的精度用不上，却会让 7×24
     * 跑着的设备每天多写近三千次。与 [setDisabled] 上那条「值没变就不落盘」同一个取向。
     * 内存里的那份（[HeartbeatSender.lastSuccessAt]）不受影响，仍然每次心跳都更新，
     * 所以界面在进程活着时依旧是实时的。
     */
    fun setLastHeartbeatAt(context: Context, at: Long) {
        val last = lastHeartbeatAt(context)
        if (last != null && at - last < HEARTBEAT_PERSIST_MIN_INTERVAL_MS) return
        get(context).edit().putLong(KEY_LAST_HEARTBEAT_AT, at).apply()
    }

    /** 网关本次启动的时刻，从未启动过返回 null。 */
    fun gatewayStartedAt(context: Context): Long? =
        get(context).getLong(KEY_GATEWAY_STARTED_AT, 0L).takeIf { it > 0L }

    /** 传 null 表示清掉（网关已停止，不再有「本次启动」可言）。 */
    fun setGatewayStartedAt(context: Context, at: Long?) {
        val editor = get(context).edit()
        if (at == null) {
            editor.remove(KEY_GATEWAY_STARTED_AT)
        } else {
            editor.putLong(KEY_GATEWAY_STARTED_AT, at)
        }
        editor.apply()
    }
}
