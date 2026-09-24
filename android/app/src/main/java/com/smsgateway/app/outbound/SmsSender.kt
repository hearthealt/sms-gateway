package com.smsgateway.app.outbound

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.smsgateway.app.model.OutboundPayload
import com.smsgateway.app.model.OutboundResult
import com.smsgateway.app.model.OutboundStatus
import com.smsgateway.app.receiver.SmsSentReceiver
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.OutboundResultStore

/**
 * 真的把一条短信发出去。
 *
 * 这是全项目唯一一处**会花钱**的地方：发一条要计费，而重发就是重复计费。
 * 所以这里的每一步都宁可失败也不重试 —— 重试由服务端决定（它不会），
 * 本地更不该自作主张。
 *
 * ### 结果分两段
 *
 * - **立刻知道的失败**：没有权限、没有可用卡、参数非法。这些直接返回一个结果，
 *   由调用方落进 [OutboundResultStore]。
 * - **交给无线电之后才知道的**：分段的发送结果由 [SmsSentReceiver] 收，收齐之后
 *   同样落进那个 store，等下一次心跳上报。
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /**
     * @return 立刻就能确定的结果；**null 表示已经交给无线电**，结果稍后由
     *   [SmsSentReceiver] 上报（那条路径无法在此返回）。
     */
    fun send(context: Context, payload: OutboundPayload): OutboundResult? {
        val app = context.applicationContext

        // 权限**不在启动时批量申请**：这个应用的主职是收码，为一个可能永远用不到的能力
        // 在首次启动就弹一个「发送短信」授权，只会让人怀疑它是干嘛的。
        // 真要用到时没有权限，就如实报一句能照着做的话。
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.write(
                app, EventLog.OUTBOUND_UNAVAILABLE, EventLog.LEVEL_WARN,
                reason = "未授予「发送短信」权限，无法发出"
            )
            // 这句话会一路回到控制台，所以要**说清下一步在哪**：那是别人手上的手机，
            // 而提权申请只能由界面发起（后台服务弹不出权限框）。
            // 应用的自检页有一条「去授权」，一点就出系统弹窗 —— 让现场照着做即可。
            return failed(payload, "本机未授予「发送短信」权限，请在那台手机上打开应用的自检页授权")
        }

        val manager = resolveManager(app, payload.simSlot)
        if (manager == null) {
            EventLog.write(
                app, EventLog.OUTBOUND_UNAVAILABLE, EventLog.LEVEL_WARN,
                reason = "没有可用的 SIM 卡"
            )
            return failed(payload, "本机没有可用的 SIM 卡")
        }

        return try {
            // divideMessage 按当前卡的编码方式分段（中文走 UCS-2 每段 67 字，
            // 英文走 GSM-7 每段 153 字）—— 这个数字直接等于计费条数，要报回服务端。
            val parts = manager.divideMessage(payload.content)
            val sentIntents = ArrayList<PendingIntent>(parts.size)

            for (index in parts.indices) {
                sentIntents.add(partPendingIntent(app, payload.key, index, parts.size))
            }

            // 投递回执（「对方收到了」）传 null 而不是挂一串 PendingIntent：
            // 国内运营商基本不下发它，挂着也只是永远不会触发的空壳。服务端那边
            // 仍然接受 DELIVERED（留给将来或第三方客户端），只是本客户端不报。
            manager.sendMultipartTextMessage(payload.phone, null, parts, sentIntents, null)
            Log.i(TAG, "交给无线电：key=${payload.key}, 分段=${parts.size}")
            // 结果由 receiver 收齐后上报
            null
        } catch (e: Exception) {
            // 受控文案：只记异常类名。异常 message 可能带上号码或正文片段，
            // 而这条事件是要留在本地库里的。
            Log.e(TAG, "发送外发短信失败：key=${payload.key}", e)
            EventLog.write(
                app, EventLog.OUTBOUND_FAILED, EventLog.LEVEL_ERROR,
                reason = "发送失败：${e.javaClass.simpleName}"
            )
            failed(payload, e.javaClass.simpleName)
        }
    }

    private fun failed(payload: OutboundPayload, reason: String) = OutboundResult(
        key = payload.key,
        status = OutboundStatus.FAILED,
        errorReason = reason
    )

    /**
     * 选一个 SmsManager。
     *
     * 双卡时必须按卡建：用默认那个会走系统当前的默认卡，而指定的卡槽是调用方明确要求的
     * （多卡机上「用哪个号发」是真实需求，收信方看到的是不同的号）。
     *
     * API 31 起 `createForSubscriptionId` 被 `getSmsManagerForSubscriptionId` 取代
     * （前者标记废弃但仍可用），所以分版本走。
     */
    private fun resolveManager(app: Context, simSlot: Int?): SmsManager? {
        val subscriptionId = resolveSubscriptionId(app, simSlot)
            ?: return runCatching { SmsManager.getDefault() }.getOrNull()

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().createForSubscriptionId(subscriptionId)
            }
        }.getOrNull()
    }

    /**
     * 指定的卡槽 → subscriptionId。
     *
     * 指定的值可能是**另一台设备**上报过来的（控制台把它存在服务端），所以要先在本机
     * 核对一下这张卡还在不在 —— 不在就退回主卡，而不是拿一个无效 id 去发（那会静默失败）。
     */
    private fun resolveSubscriptionId(app: Context, simSlot: Int?): Int? {
        val slots = DevicePhone.listSlots(app)
        if (simSlot != null && slots.any { it.subscriptionId == simSlot }) {
            return simSlot
        }
        if (simSlot != null) {
            Log.w(TAG, "指定的卡槽 $simSlot 在本机不存在，改用主卡")
        }
        return DevicePhone.primarySlot(app)?.subscriptionId
    }

    private fun partPendingIntent(app: Context, key: String, index: Int, partCount: Int): PendingIntent {
        val intent = Intent(app, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SENT
            putExtra(SmsSentReceiver.EXTRA_KEY, key)
            putExtra(SmsSentReceiver.EXTRA_PART_INDEX, index)
            putExtra(SmsSentReceiver.EXTRA_PART_COUNT, partCount)
        }
        // requestCode 必须每条、每一段都不同：相同的话 PendingIntent 会被复用，
        // 后来的那一段会覆盖掉前面的 extras，于是只有最后一段的结果能收到。
        return PendingIntent.getBroadcast(
            app,
            requestCode(key, index, 0),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun requestCode(key: String, index: Int, kind: Int): Int =
        (key.hashCode() * 31 + index * 7 + kind) and 0x7FFFFFFF
}
