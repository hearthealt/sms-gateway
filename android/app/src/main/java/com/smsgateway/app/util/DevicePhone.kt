package com.smsgateway.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/** 一张 SIM 卡。number 为 null 表示这张卡没有写入号码（很常见）。 */
data class SimSlot(
    val subscriptionId: Int,
    val label: String,
    val number: String?
)

/**
 * 列卡的结果。
 *
 * 带上 [problem] 而不是只回一个列表：读不到卡时必须说得出是**哪一种**读不到
 * （没权限 / 系统不给列表 / 调不通），否则界面上能看到的只有「点了没反应」——
 * 那正是这个功能之前被投诉的样子。它不是异常，是给用户看的一句话。
 */
data class SimSlotsResult(
    val slots: List<SimSlot>,
    /** null 表示一切正常。 */
    val problem: String?
)

/**
 * 尽力读取本机号码，并支持双卡时逐张列出。
 *
 * 这条路大概率读不到：号码存在 SIM 卡上，而多数现代运营商并不往卡里写，
 * 所以读取返回空是常态、不是故障。
 * 因此它只用来做一次预填，设置页里手填的值才是权威来源。
 */
object DevicePhone {

    private const val TAG = "DevicePhone"

    /**
     * 需要申请的全部权限。
     *
     * READ_PHONE_NUMBERS 用于读号码（API 33 起 getLine1Number 不再接受 READ_PHONE_STATE），
     * 而**列 SIM 卡列表**用的 SubscriptionManager.getActiveSubscriptionInfoList()
     * 要的是 READ_PHONE_STATE —— 少任何一个，双卡选择都会静默变成空列表。
     * 两者同属 PHONE 权限组，系统只弹一次。
     */
    val requiredPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE)
        } else {
            listOf(Manifest.permission.READ_PHONE_STATE)
        }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 两个号码是不是同一个号。
     *
     * 只比数字，且允许一边带国家码：同一张卡，[read] 给的是 `+8613800138000`，
     * 用户手填的可能是 `13800138000`，系统里存的可能带空格或横线。直接字符串
     * 相等会把它们判成两个号，于是该补的归属补不上。
     *
     * 短于 [MIN_COMPARE_DIGITS] 位的一律判为不同：后缀比较在短串上会误判
     * （`087` 和 `1087` 的结尾是一样的）。
     */
    fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.length < MIN_COMPARE_DIGITS || db.length < MIN_COMPARE_DIGITS) return false
        return da == db || da.endsWith(db) || db.endsWith(da)
    }

    private const val MIN_COMPARE_DIGITS = 7

    fun hasPermission(context: Context): Boolean =
        requiredPermissions.all { isGranted(context, it) }

    /** 默认卡的本机号码；无权限或读不到时返回 null。 */
    fun read(context: Context): String? = primarySlot(context)?.number ?: readDefault(context)

    /**
     * 第一张能读出号码的卡。
     *
     * 用于自动预填：预填的同时要记住号码属于哪张卡（见 DevicePrefs.KEY_PHONE_SUB_ID），
     * 否则将来这张卡读不到号码时，无法判断一条短信是不是来自另一张卡。
     */
    fun primarySlot(context: Context): SimSlot? =
        listSlots(context).firstOrNull { !it.number.isNullOrBlank() }

    /**
     * 列出所有 SIM 卡。无权限或取不到时返回空列表。
     *
     * 取号码对每张卡单独尝试：双卡机上默认卡的号码常常读不到，而副卡反而有。
     */
    fun listSlots(context: Context): List<SimSlot> = querySlots(context).slots

    /**
     * 这台设备的卡槽数。**不需要任何权限**，这是它唯一的用处。
     *
     * 用 [TelephonyManager.getActiveModemCount]（API 30 起，之前是已弃用的 `phoneCount`）：
     * 它报的是硬件/系统层面的调制解调器数量，与有没有 READ_PHONE_STATE 无关 ——
     * 而 [querySlots] 走的是 SubscriptionManager，没有权限就一个字都问不出来。
     *
     * 取不到（服务缺失、ROM 抛异常）时返回 0，调用方必须把 0 当成「不知道」而不是
     * 「零张卡」：把不确定当成确定，正是下面那个判据要避免的事。
     */
    @Suppress("DEPRECATION")
    fun activeModemCount(context: Context): Int = try {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        when {
            manager == null -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> manager.activeModemCount
            else -> manager.phoneCount
        }
    } catch (e: Exception) {
        Log.w(TAG, "activeModemCount 失败", e)
        0
    }

    /**
     * 这台设备**确定**只有一张卡吗。
     *
     * 这是 [SmsReceiver.resolveSmsPhone] 判定「能不能拿配置里的号码顶上」的依据。
     * 条件是「确定」而不是「大概」：这个问题的两个方向代价不对称 ——
     * 判成单卡而其实是双卡，副卡收到的验证码会被标成主卡的号码，调用方拿到**错答案**；
     * 判成双卡而其实是单卡，只是号码留空，调用方等不到码、最后超时。
     * 超时能被发现，错答案不能。
     *
     * 两条路，优先那条准的：
     * 1. 有电话权限时以**激活的卡列表**为准（双卡槽只插一张卡也判得对）；
     * 2. 没有权限时退回卡槽数 —— 它不需要权限，代价是「双卡槽只插一张卡」会被当成双卡，
     *    也就是退化成「留空」。这一档救的正是原先最冤的那批设备：单卡机 + 用户拒了权限。
     *    （见 [SmsReceiver] 的说明：那种情况下原先每一条短信都以空号码上传。）
     */
    fun isSingleSim(context: Context): Boolean {
        val slots = querySlots(context)
        if (slots.problem == null) return slots.slots.size <= 1
        return activeModemCount(context) == 1
    }

    /**
     * 列卡，并带上「为什么是空的」。
     *
     * 拿不到 [SubscriptionManager.getActiveSubscriptionInfoList] 时**不是直接给空**，
     * 而是退回默认卡：给出一张「默认卡」看起来信息少，但比一个空列表强得多 ——
     * 空列表的后果是双份的：SIM 选择器打不开，而且预填拿不到 subId（退回 -1），
     * 多卡机上「这条短信是哪张卡来的」就判断不了（见 SmsReceiver.resolveSmsPhone）。
     *
     * 这条兜底在手上的 Redmi K60 Ultra（Android 14）上**没有被触发**：那台机器
     * 权限给全之后 activeSubscriptionInfoList 正常返回一条。留着是因为它属于
     * 「ROM 不给列表」那一类已知差异，而代价只是一次静态调用。
     */
    fun querySlots(context: Context): SimSlotsResult {
        if (!hasPermission(context)) {
            Log.i(TAG, "querySlots: 电话权限未授予")
            return SimSlotsResult(emptyList(), "未授予电话权限")
        }

        return try {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            if (manager == null) {
                Log.w(TAG, "querySlots: 取不到 SubscriptionManager")
                return SimSlotsResult(emptyList(), "系统没有提供卡管理服务")
            }

            @Suppress("MissingPermission")
            val infos: List<SubscriptionInfo> = manager.activeSubscriptionInfoList ?: emptyList()

            if (infos.isNotEmpty()) {
                return SimSlotsResult(
                    slots = infos.mapIndexed { index, info ->
                        val subId = info.subscriptionId
                        val name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                            ?: "SIM ${index + 1}"
                        SimSlot(
                            subscriptionId = subId,
                            label = "$name（卡槽 ${info.simSlotIndex + 1}）",
                            number = readNumber(context, subId, info)
                        )
                    },
                    problem = null
                )
            }

            // 空不等于「没有卡」：有可能是这张列表被 ROM 挡了，此时默认卡那条路还能走
            Log.w(TAG, "querySlots: activeSubscriptionInfoList 为空，退回默认卡")

            val fallback = defaultSlot(context)
            if (fallback != null) {
                SimSlotsResult(listOf(fallback), null)
            } else {
                SimSlotsResult(emptyList(), "系统没有返回已激活的 SIM 卡")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "querySlots: 缺权限", e)
            SimSlotsResult(emptyList(), "缺少读取 SIM 卡的权限")
        } catch (e: Exception) {
            Log.w(TAG, "querySlots 失败", e)
            SimSlotsResult(emptyList(), "读取 SIM 卡信息失败（${e.javaClass.simpleName}）")
        }
    }

    /**
     * 拿不到订阅列表时的兜底：把默认卡给出来。
     *
     * 号码走 [readNumber]（其中 `TelephonyManager` 那条不需要订阅列表），
     * subId 取自 [SubscriptionManager.getDefaultSubscriptionId] —— 有它，
     * 号码归属就能落进 `DevicePrefs.KEY_PHONE_SUB_ID`，而不是退成 -1。
     */
    @Suppress("MissingPermission")
    private fun defaultSlot(context: Context): SimSlot? {
        val subId = try {
            SubscriptionManager.getDefaultSubscriptionId()
        } catch (e: Exception) {
            Log.w(TAG, "defaultSlot: 取默认卡失败", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null

        return SimSlot(
            subscriptionId = subId,
            label = "默认卡（系统未返回卡列表）",
            number = readNumber(context, subId, findSubscriptionInfo(context, subId))
        )
    }

    /** 读指定卡的本机号码。 */
    fun readForSubscription(context: Context, subscriptionId: Int): String? {
        if (!hasPermission(context)) return null
        return readNumber(context, subscriptionId, findSubscriptionInfo(context, subscriptionId))
    }

    /**
     * 这个值是不是一台**真实存在**的卡的 subId。
     *
     * 广播里那个 `subscription` extra 没有公开 API 定义（见 SmsReceiver 的说明），
     * 历史 ROM 往里放的是卡槽号（0/1）之类的别的值。拿一个不是 subId 的数字去查
     * SubscriptionManager，轻则查不到，重则**恰好撞上另一张卡的 subId** ——
     * 那条短信就会被标成另一张卡的号码，而服务端是按号码缓存验证码的。
     *
     * 查询失败一律返回 false：调用方在 false 时的行为是「留空」，
     * 那是延迟；标错号码是错答案，更难查。
     */
    fun isKnownSubscription(context: Context, subscriptionId: Int): Boolean {
        if (subscriptionId < 0) return false
        return try {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager ?: return false
            manager.activeSubscriptionInfoList?.any { it.subscriptionId == subscriptionId } == true
        } catch (e: Exception) {
            Log.w(TAG, "isKnownSubscription: 查卡列表失败", e)
            false
        }
    }

    /**
     * 读号码：三条路依次试，取第一个非空的。
     *
     * 为什么要三条：同一个号码在不同 ROM / 运营商组合下，能被读出来的那条路并不一样，
     * 而「读不到」在界面上和「卡里本来就没写」长得一模一样。多试一条路的成本只是
     * 一次内存读取，少一条就是设置页上那句「读不到号码」。
     *
     * 1. [SubscriptionManager.getPhoneNumber]：API 33 起的正路。
     * 2. [SubscriptionInfo.getNumber]：SIM 卡记录里存的那一份（自 API 33 起被标记弃用，
     *    但弃用的理由是「有更好的 API」，不是「这个不能用」）。
     * 3. [TelephonyManager.getLine1Number]：老系统的路，也作为最后一次兜底。
     *
     * 三条都失败是常态（多数现代运营商根本不往卡里写号码），不是故障：
     * 界面上给的是「读不到号码，请手动填写」，手填的值才是权威来源（见 DevicePrefs.setPhone）。
     */
    @Suppress("DEPRECATION")
    private fun readNumber(
        context: Context,
        subscriptionId: Int,
        info: SubscriptionInfo?
    ): String? {
        if (!hasPermission(context)) return null
        return numberFromSubscriptionManager(context, subscriptionId)
            ?: info?.number?.takeIf { it.isNotBlank() }
            ?: numberFromTelephonyManager(context, subscriptionId)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun numberFromSubscriptionManager(context: Context, subscriptionId: Int): String? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                manager?.getPhoneNumber(subscriptionId)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }

    // line1Number 自 API 33 起被标记弃用，但它是老系统上唯一的路，而且弃用的理由是
    // 「不可靠」而不是「不能用」—— 这里本来就只当尽力而为的预填，继续用。
    @Suppress("MissingPermission", "DEPRECATION")
    private fun numberFromTelephonyManager(context: Context, subscriptionId: Int): String? =
        try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.createForSubscriptionId(subscriptionId)
                ?.line1Number
                ?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }

    @Suppress("MissingPermission")
    private fun findSubscriptionInfo(context: Context, subscriptionId: Int): SubscriptionInfo? =
        try {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            manager?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subscriptionId }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }

    /**
     * 不指定卡时的默认读取。
     *
     * getLine1Number() 在 API 33 起被标记为弃用，替代品同样依赖 SIM 卡里存了这个号码 ——
     * 弃用的原因是「不可靠」，而不是「不能用」。既然这里本来就只当尽力而为的预填，
     * 就继续用它。
     */
    @Suppress("DEPRECATION")
    private fun readDefault(context: Context): String? {
        if (!hasPermission(context)) return null

        return try {
            val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            manager?.line1Number?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
