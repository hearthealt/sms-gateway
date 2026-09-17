package com.smsgateway.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** 一张 SIM 卡。number 为 null 表示这张卡没有写入号码（很常见）。 */
data class SimSlot(
    val subscriptionId: Int,
    val label: String,
    val number: String?
)

/**
 * 尽力读取本机号码，并支持双卡时逐张列出。
 *
 * 这条路大概率读不到：号码存在 SIM 卡上，而多数现代运营商并不往卡里写，
 * 所以读取返回空是常态、不是故障。
 * 因此它只用来做一次预填，设置页里手填的值才是权威来源。
 */
object DevicePhone {

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
    fun listSlots(context: Context): List<SimSlot> {
        if (!hasPermission(context)) return emptyList()

        return try {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager ?: return emptyList()

            @Suppress("MissingPermission")
            val infos: List<SubscriptionInfo> = manager.activeSubscriptionInfoList ?: emptyList()

            infos.mapIndexed { index, info ->
                val subId = info.subscriptionId
                val name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "SIM ${index + 1}"
                SimSlot(
                    subscriptionId = subId,
                    label = "$name（卡槽 ${info.simSlotIndex + 1}）",
                    number = readForSubscription(context, subId)
                )
            }
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 读指定卡的本机号码。
     *
     * API 33 起用 SubscriptionManager.getPhoneNumber()（旧的 getLine1Number() 已被弃用，
     * 但对老系统仍要保留）。两者不可靠的原因相同：卡里没写号码。
     */
    @Suppress("DEPRECATION")
    fun readForSubscription(context: Context, subscriptionId: Int): String? {
        if (!hasPermission(context)) return null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                manager?.getPhoneNumber(subscriptionId)?.takeIf { it.isNotBlank() }
            } else {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as? TelephonyManager
                telephony?.createForSubscriptionId(subscriptionId)
                    ?.line1Number
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
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
