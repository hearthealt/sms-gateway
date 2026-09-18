package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.parser.SmsCodeParser
import com.smsgateway.app.parser.SmsFilter
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        /**
         * 广播里携带「哪张卡收到的」的 extra key。
         *
         * Android 至今没有公开 API 暴露它（SmsMessage.getSubscriptionId 是 @SystemApi），
         * 但这个字符串 extra 从 AOSP 到各厂商 ROM 一直带着，是通行做法。取不到时为 -1。
         */
        private const val SUBSCRIPTION_KEY = "subscription"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        // Intent 解包必须在主线程当场做完，goAsync 之后不应再触碰 intent
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress ?: ""
        val receiveTime = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val subscriptionId = intent.getIntExtra(SUBSCRIPTION_KEY, -1)

        // goAsync 让广播在 onReceive 返回后仍持有进程。否则下面的 IO 协程可能来不及写库
        // 就被系统杀掉，短信直接丢失 —— 「未注册也先入库、注册后再上传」的前提正是这一行。
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Filter: only collect matching SMS
                if (!SmsFilter.shouldCollect(sender, fullBody)) return@launch

                // 2. Parse verification code —— 解析不出来的短信没有上报价值（调用方要的就是验证码）
                val code = SmsCodeParser.parse(fullBody)
                if (code.isNullOrBlank()) return@launch

                // 3. Generate unique local message ID
                val localMessageId = "sms-${sender}-${receiveTime}-${fullBody.hashCode().toUShort()}"

                // 4. Enqueue to Room database
                // deviceId 取本机已注册的信息；未注册时为空串，注册成功后
                // DashboardViewModel 会调 backfillIdentity() 补上并触发上传。
                val entity = SmsQueueEntity(
                    localMessageId = localMessageId,
                    deviceId = DevicePrefs.deviceId(context),
                    phone = resolveSmsPhone(context, subscriptionId),
                    sender = sender,
                    content = fullBody,
                    code = code,
                    receiveTime = receiveTime
                    // status / retryCount / nextRetryAt 走实体默认值：
                    // 默认 status 即 DAO 查询用的 "pending"，nextRetryAt = 0 表示立即可上传
                )

                try {
                    AppDatabase.getInstance(context).smsQueueDao().insert(entity)
                } catch (e: Exception) {
                    android.util.Log.e("SmsReceiver", "Failed to save SMS to database", e)
                }

                // 5. Trigger upload worker —— 未注册、被禁用、**或网关已停止**时只入库不发送。
                //
                // 前两种是「传了也白传」；第三种是产品承诺：界面上停止网关时写着
                // 「短信会留在本地，不会上报」，而 WorkManager 会把进程拉起来照跑 worker ——
                // 不在这里挡住，停止按钮就等于没生效（现场已经踩过）。
                //
                // 恢复时（注册成功 / 心跳看到已启用 / 网关重新启动）会重新排一次，
                // 那时这些行仍是 pending，会被一起补传。
                if (DevicePrefs.isRegistered(context) &&
                    !DeviceStatus.isDisabled(context) &&
                    GatewayState.isRunning(context)
                ) {
                    SmsUploadWorker.enqueue(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 这条短信该记为哪个号码。
     *
     * 多卡时必须记「收到它的那张卡」的号码，而不是设备上存的那一个 ——
     * 服务端按号码缓存验证码（sms:code:{号码}），标错号码会让等待某张卡验证码的
     * 调用方拿到另一张卡的码。错答案比超时更糟，所以下面宁可留空。
     */
    private fun resolveSmsPhone(context: Context, subscriptionId: Int): String {
        if (subscriptionId >= 0) {
            DevicePhone.readForSubscription(context, subscriptionId)?.let { return it }
        }

        val storedSubId = DevicePrefs.phoneSubId(context)
        val fromDifferentSim =
            subscriptionId >= 0 && storedSubId >= 0 && subscriptionId != storedSubId

        // 明知这条来自另一张卡、却读不到那张卡的号码：留空（服务端会跳过按号码缓存），
        // 绝不能用设备上存的号码顶上。
        if (fromDifferentSim) return ""

        // 单卡、或号码是手动填的（不知属于哪张卡）→ 回落到设备上配置的号码
        return DevicePrefs.phone(context)
    }
}
