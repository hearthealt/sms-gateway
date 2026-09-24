package com.smsgateway.app.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.smsgateway.app.model.OutboundResult
import com.smsgateway.app.model.OutboundStatus
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.OutboundResultStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 外发短信的发送结果。
 *
 * 每发一段就会收到一次回调，所以这里要**攒够所有段**才能给出结论：
 * 一条被拆成 3 段的短信，前两段成功、第三段失败时，整体是**失败** ——
 * 对方收到的是半截内容，与没收到一样没用。
 *
 * 攒在进程内的一个 map 里。进程在段与段之间被杀会丢掉结论，那条就停在
 * 服务端的「已下发」、10 分钟后被清理任务判成「结果未知」——
 * 这是刻意的：那种情况下我们**真的不知道**发出去没有，如实说比猜一个好。
 */
class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsSentReceiver"

        const val ACTION_SENT = "com.smsgateway.app.SMS_SENT"
        const val EXTRA_KEY = "outbound_key"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"

        /**
         * key → 各段的结果。null 表示那一段还没回来。
         *
         * 段数很少（一般 1 段），map 也不会大；用 ConcurrentHashMap 是因为
         * 回调可能来自不同线程（系统广播投递）。
         */
        private val parts = ConcurrentHashMap<String, Array<Int?>>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceAtLeast(1)
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0).coerceIn(0, partCount - 1)

        val slots = parts.getOrPut(key) { arrayOfNulls(partCount) }
        // 同一个段重复回调时后到的覆盖先到的：最后那个结果才是这条消息的真实状态
        slots[partIndex] = resultCode

        if (slots.any { it == null }) {
            Log.d(TAG, "key=$key 已收到 ${slots.count { it != null }}/$partCount 段结果")
            return
        }

        parts.remove(key)
        val result = buildResult(key, partCount, slots)
        OutboundResultStore.record(app, OutboundResultStore.Entry(
            key = result.key,
            status = result.status,
            errorReason = result.errorReason,
            segments = result.segments
        ))

        EventLog.write(
            app,
            if (result.status == OutboundStatus.SENT) EventLog.OUTBOUND_SENT else EventLog.OUTBOUND_FAILED,
            if (result.status == OutboundStatus.SENT) EventLog.LEVEL_INFO else EventLog.LEVEL_ERROR,
            reason = if (result.status == OutboundStatus.SENT) {
                "已交给运营商（$partCount 段）"
            } else {
                "发送失败：${result.errorReason ?: "未知"}（$partCount 段）"
            }
        )
        Log.i(TAG, "key=$key 结果=${result.status}, 段数=$partCount")
    }

    /**
     * 全部段都回来了，给一个总结论。
     *
     * 任何一段失败就是失败：分段的短信少一段，对方看到的是残缺内容，
     * 而「发出去了 2/3 段」这种结论对使用方毫无意义。原因取**第一段失败的**那一句，
     * 那通常是最接近根因的（后面几段往往是因为同一条链路连着失败）。
     */
    private fun buildResult(key: String, partCount: Int, codes: Array<Int?>): OutboundResult {
        val failed = codes.filter { it != Activity.RESULT_OK }
        return if (failed.isEmpty()) {
            OutboundResult(key, OutboundStatus.SENT, segments = partCount)
        } else {
            OutboundResult(
                key,
                OutboundStatus.FAILED,
                errorReason = describe(failed.first()),
                segments = partCount
            )
        }
    }

    /**
     * 失败码 → 一句能照着排查的话。
     *
     * **受控文案**：这些字符串会被写进本地事件表、再随心跳回到服务端的表里，
     * 所以写死的短语而不是拼异常信息。中文而不是常量名 —— 这一句最终是给
     * 控制台前面的人看的（「无信号」比 RESULT_ERROR_NO_SERVICE 有用）。
     */
    private fun describe(code: Int?): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "无信号"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "飞行模式"
        SmsManager.RESULT_ERROR_NULL_PDU -> "内容无效"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "运营商拒绝"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "不支持该短信类"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "被系统禁止"
        // 其余（含系统未公开的那几个码）原样带上数字：数字至少是可查的，
        // 而编一句「未知错误」会让现场连搜都没得搜。
        else -> "发送失败（码 ${code ?: -1}）"
    }
}
