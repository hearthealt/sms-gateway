package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.SmsIngest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `SMS_RECEIVED` 广播的入口。
 *
 * 它只做三件事：**解包**（必须在主线程当场做完）、**留痕**（广播到了却解不出东西时）、
 * 然后把结果**转交**给 [SmsIngest]。
 *
 * 入库、号码归属、去重、排上传全部在 [SmsIngest] 里，因为还有第二条路会送短信进来 ——
 * [com.smsgateway.app.util.SmsInboxReconciler] 读系统短信库补采广播**漏投**的短信。
 * 两条路必须共用同一段逻辑，否则 [com.smsgateway.app.util.LocalMessageId] 的入参
 * 一旦分叉，同一条短信会被入两行、传两次。理由写在 SmsIngest 的类注释里。
 *
 * 广播本身**不可靠**：2026-09-30 现场，一条腾讯视频验证码在系统收件箱里、而本类
 * 从未被调用（队列为空、事件表零记录），且进程健康、心跳正常。所以这里不再是唯一的
 * 入口，对账腿才是「广播漏投不会导致永久丢失」的保证。
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * 广播里携带「哪张卡收到的」的 extra key。
         *
         * Android 至今没有公开 API 暴露它（SmsMessage.getSubscriptionId 是 @SystemApi），
         * 但这个字符串 extra 从 AOSP 到各厂商 ROM 一直带着，是通行做法。取不到时为 -1。
         */
        private const val SUBSCRIPTION_KEY = "subscription"

        /**
         * 「广播到了但解不出短信」是否已经报过。
         *
         * 畸形 PDU 一旦出现，往往是同一台 ROM 的持续行为 —— 逐次记会把 7 天的事件表
         * 刷成同一条，真正重要的事件反而被埋掉。进程内报一次就够现场知道这件事存在。
         * 与 HeartbeatSender 里那个 failureReported 是同一套写法。
         *
         * 接收器在 manifest 上带 android:permission="android.permission.BROADCAST_SMS"，
         * 只有系统打得进来，所以不存在被第三方反复触发而刷表的风险。
         */
        @Volatile
        private var reportedUndecodable = false
    }

    /**
     * 记一条「广播到了，但这条短信没能进队列」，并**持有进程直到写完**。
     *
     * 必须 goAsync + writeNow：这个函数只在那两个 `return` 之前的分支里调用，
     * 那时进程只是个缓存进程，onReceive 一返回就随时可能被回收 ——
     * 而 fire-and-forget 的 [EventLog.write] 跑在它自建的 scope 里，很可能一起被带走。
     * 这条事件的**全部意义**就是让这条路径可见（原先它与「广播根本没交到应用」
     * 在别处完全无法区分），被丢掉等于没写。
     *
     * 复用 SMS_ENQUEUE_FAILED 而不是新增事件类型：它本来就表示「这条短信没能进队列」，
     * 具体是数据库写失败还是 PDU 根本解不开，由 reason 区分。
     *
     * 放在类里而不是 companion：`goAsync()` 是接收器实例的方法。节流标志本身必须是
     * 静态的 —— 系统每次广播都可能新建一个 receiver 实例。
     */
    private fun reportUndecodable(context: Context, reason: String) {
        if (reportedUndecodable) return
        reportedUndecodable = true

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EventLog.writeNow(
                    context, EventLog.SMS_ENQUEUE_FAILED, EventLog.LEVEL_ERROR,
                    reason = "广播到了但解不出短信：$reason"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record undecodable broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) {
            // 理论上到不了这里（manifest 的 intent-filter 只声明了这一个 action），
            // 但真发生时原先是一行痕迹都不留。
            Log.d(TAG, "Ignoring non-SMS action: ${intent.action}")
            return
        }

        // 解包放进 try：畸形 PDU 会让 getMessagesFromIntent 抛异常（各 ROM 表现不一），
        // 而这里跑在**主线程** —— 未捕获就是应用崩溃。一台无人值守的网关崩了没人知道，
        // 而且崩溃现场连一条事件都留不下。
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode SMS PDUs", e)
            reportUndecodable(context, e.javaClass.simpleName)
            return
        }

        // 空数组同样要留痕：pdus extra 缺失或损坏时 getMessagesFromIntent 不抛异常、
        // 只返回空数组。这一支与「广播根本没交到应用」在现场长得一模一样
        // （队列没有、服务端没有、logcat 里也没有），原先两者根本无法区分。
        if (messages.isEmpty()) {
            reportUndecodable(context, "空广播")
            return
        }

        // Intent 解包必须在主线程当场做完，goAsync 之后不应再触碰 intent
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
        // sender 允许为空串。个别 PDU 解出的 originatingAddress 就是 null，这时唯一
        // 正确的做法是如实上报空值 —— 后端已放开对 sender 的非空校验（理由见 SmsReceiveRequest），
        // 绝不能在这里编一个占位号码，那会让这条短信挂到别人名下。
        val sender = messages.firstOrNull()?.originatingAddress ?: ""

        // 取**非 0** 的最小时间戳。原先直接 minOfOrNull，有两个问题：
        //  - minOf 是「取最小」：只要有一个分片的 SCTS 缺失（AOSP 会把 timestampMillis
        //    留成 0），整体就取到 0。而这一维要去重键里当「收到时刻」用，取到 0 会让键
        //    退化成 sms-0-<卡槽>-<哈希>，同一号码连发两条同样内容的短信就会撞键，
        //    第二条被当成重投静默丢掉。
        //  - 后面那个 `?:` 是死代码：空列表在更早处已经 return 了。
        val receiveTime = messages.map { it.timestampMillis }.filter { it > 0 }.minOrNull()
            ?: System.currentTimeMillis()

        // 这个 extra 没有公开 API 定义（见 SUBSCRIPTION_KEY 的说明），取不到时是 -1。
        val subscriptionId = intent.getIntExtra(SUBSCRIPTION_KEY, -1)

        // goAsync 让广播在 onReceive 返回后仍持有进程。否则下面的 IO 协程可能来不及写库
        // 就被系统杀掉，短信直接丢失 —— 「未注册也先入库、注册后再上传」的前提正是这一行。
        val pendingResult = goAsync()

        // 拿到手的四个入参就在这里定格，之后全部交给 SmsIngest。这里**不要**再解 intent：
        // goAsync 之后 intent 随时可能失效。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsIngest.ingest(
                    context = context,
                    sender = sender,
                    body = fullBody,
                    receiveTime = receiveTime,
                    subscriptionId = subscriptionId,
                    source = SmsIngest.Source.BROADCAST
                )
            } finally {
                // ingest 自己吞掉全部异常（CancellationException 除外，那个要放行）。
                // 这里只负责把广播放掉 —— 少了这一行，接收器会被系统按超时杀掉。
                pendingResult.finish()
            }
        }
    }
}
