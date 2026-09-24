package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.parser.SmsFilter
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.LocalMessageId
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        /** 见 [EventLog.SMS_NO_PHONE]。同样是进程内只报一次。 */
        @Volatile
        private var reportedNoPhone = false

        /**
         * 收信号码读不到时留一条痕。
         *
         * 这条短信**传得上去**（服务端的 phone 允许为空），但服务端会跳过写
         * `sms:code:{号码}`，于是按号码等码的调用方永远等不到 —— 而设备侧记的是
         * 「上传成功」、队列行也正常消失，三处都没有任何异常信号。
         *
         * 用 writeNow 而不是 fire-and-forget：这条事件的全部意义就是让它可见，
         * 而被丢掉的事件等于没写。节流之后一个进程只写一条，代价可以忽略。
         */
        private suspend fun reportMissingPhoneOnce(context: Context, phone: String) {
            if (phone.isNotBlank()) return
            if (reportedNoPhone) return
            reportedNoPhone = true
            EventLog.writeNow(
                context, EventLog.SMS_NO_PHONE, EventLog.LEVEL_WARN,
                // 标签已经说了「号码未知」，这里只说后果 —— 事件行要短，
                // 一句写满一整行的话在列表里没人读得下去。
                reason = "按号码等验证码的调用方会超时"
            )
        }
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 收信卡号只解析一次，下面每一处都用它 —— **包括被过滤掉的那两条路径**。
                //
                // 多卡设备上这是「为什么这条没转发」的关键一维：调用方等的验证码是按号码
                // 缓存和匹配的，不知道是哪个号收到的，就分不清「这张卡没收到」和
                // 「收到了但标错了号码」。而那两条路径**没有队列行**，这里是唯一的归属信息。
                val phone = try {
                    resolveSmsPhone(context, subscriptionId)
                } catch (e: Exception) {
                    // 解析内部要走 SubscriptionManager 的跨进程调用，ROM 上抛异常并不罕见。
                    // **绝不能让它把这条短信带走**：号码归零的代价是「服务端不写按号码的
                    // 验证码缓存」，那是延迟；丢短信不是。
                    Log.w(TAG, "Failed to resolve receiving SIM number", e)
                    ""
                }

                // 1. Filter: only collect matching SMS
                if (!SmsFilter.shouldCollect(sender, fullBody)) {
                    // 按设计丢弃，但要留痕：现场「我明明发了验证码却没转发」时，
                    // 这一条是唯一能区分「广播根本没到」和「到了但被过滤掉」的证据 ——
                    // 两者在别处长得一模一样（队列没有、服务端没有）。
                    //
                    // **刻意不带 sender**：这一支过滤掉的正是「我们决定不要」的短信，
                    // 把它们的发送方号码再留 7 天，与「不入库」这个决定自相矛盾；
                    // 而它们又是量最大的一类，扛着号码会把日志页刷成收件箱镜像。
                    // 留下收信号码与时刻，足以回答「那一刻到底有没有短信进来」。
                    EventLog.writeNow(
                        context, EventLog.SMS_FILTERED, EventLog.LEVEL_INFO,
                        phone = phone, reason = "未命中采集关键词"
                    )
                    return@launch
                }

                // 2. 本地不再解析验证码 —— 认码只留服务端一处。
                //
                // 原先这里有一道 `SmsCodeParser.parse()`，解析不出来就把短信丢弃
                // （写 SMS_NO_CODE 后 return，**根本不上传**）。那是最贵的一道损失闸门：
                //   - 客户端的规则比服务端窄：`您的安全码是483920`、繁体、裸英文 `code`
                //     这些连 filter 都过不去，服务端根本没机会看到；
                //   - 客户端解析出来的值又会**压过后端**（SmsService.resolveCode 原先
                //     优先采用客户端值），而它更宽、会返回错码（`流水号 123456` → 123456，
                //     `验证码是1234567890` → 12345678），错码被写进 sms:code 缓存推给等待方。
                // 于是客户端同时是最宽的错答案来源和最窄的宽度上限。
                //
                // 现在：客户端只做关键词过滤 + 上报，码由服务端从正文提取（CodeExtractor）。
                // 那边认不出的码才是全链路认不出的码，改规则也只需改一处、不必发版。

                // 3. Generate unique local message ID
                // 格式与理由见 LocalMessageId：老格式的低 16 位截断会让「同一号码在同一秒
                // 发来的两条不同正文」撞成同一条，第二条被静默丢弃并记成「重复短信」；
                // 而「同一张卡、同一秒、正文相同、发送方不同」那一维此前根本没进键里。
                val localMessageId =
                    LocalMessageId.build(receiveTime, subscriptionId, fullBody, sender)

                // 4. Enqueue to Room database
                // deviceId 取本机已注册的信息；未注册时为空串，注册成功后
                // DashboardViewModel 会调 backfillIdentity() 补上并触发上传。
                val entity = SmsQueueEntity(
                    localMessageId = localMessageId,
                    deviceId = DevicePrefs.deviceId(context),
                    phone = phone,
                    sender = sender,
                    content = fullBody,
                    // 恒为空：不上传、也不用于本地展示。留着这一列是为了免掉一次迁移
                    // （表里是还没上传的短信，动它就是动用户资产），它的值由服务端重新算。
                    code = "",
                    receiveTime = receiveTime
                    // status / retryCount / nextRetryAt 走实体默认值：
                    // 默认 status 即 DAO 查询用的 "pending"，nextRetryAt = 0 表示立即可上传
                )

                // insert 的返回值必须看。DAO 上是 `OnConflictStrategy.IGNORE`，
                // 而 localMessageId 上有唯一索引 —— 撞索引时**既不抛异常、也不报错**，
                // 静默返回 -1。原先这里把返回值直接丢掉，于是「短信没入库」这件事
                // 在服务端和本地队列里都查不到，成了一条完全无声的丢失路径。
                // >0 是新插入的行 id；-1 是撞了唯一索引（同一条短信重投，属正常）。
                var rowId = -1L
                try {
                    rowId = AppDatabase.getInstance(context).smsQueueDao().insert(entity)
                    if (rowId > 0) {
                        EventLog.writeNow(
                            context, EventLog.SMS_ENQUEUED, EventLog.LEVEL_INFO,
                            sender = sender, phone = phone, smsId = rowId
                        )
                    } else {
                        EventLog.writeNow(
                            context, EventLog.SMS_DUPLICATE, EventLog.LEVEL_WARN,
                            sender = sender, phone = phone,
                            reason = "localMessageId 已存在（短信重投）"
                        )
                    }
                } catch (e: Exception) {
                    EventLog.writeNow(
                        context, EventLog.SMS_ENQUEUE_FAILED, EventLog.LEVEL_ERROR,
                        sender = sender, phone = phone, reason = e.javaClass.simpleName
                    )
                    Log.e(TAG, "Failed to save SMS to database", e)
                }

                // 4.5 号码读不到不等于上传失败，但它的后果是「传上去了却没人取得到」——
                // 唯一的信号就在这里，见 reportMissingPhoneOnce。
                reportMissingPhoneOnce(context, phone)

                // 5. Trigger upload worker —— 未注册、被禁用、**或网关已停止**时只入库不发送。
                //
                // 前两种是「传了也白传」；第三种是产品承诺：界面上停止网关时写着
                // 「短信会留在本地，不会上报」，而 WorkManager 会把进程拉起来照跑 worker ——
                // 不在这里挡住，停止按钮就等于没生效（现场已经踩过）。
                //
                // 恢复时（注册成功 / 心跳看到已启用 / 网关重新启动）会重新排一次，
                // 那时这些行仍是 pending，会被一起补传。
                //
                // 三种原因分开记，而不是合成一个「没上传」：现场最容易误判的就是这一支 ——
                // 短信明明在队列里、界面也显示正常，用户却以为丢了。日志里说清是哪种。
                val heldReason = when {
                    !DevicePrefs.isRegistered(context) -> "未注册"
                    DeviceStatus.isDisabled(context) -> "设备已被禁用"
                    !GatewayState.isRunning(context) -> "网关已停止"
                    else -> null
                }
                if (heldReason != null) {
                    EventLog.writeNow(
                        context, EventLog.SMS_HELD, EventLog.LEVEL_WARN,
                        sender = sender, phone = phone, reason = heldReason,
                        smsId = rowId.takeIf { it > 0 }
                    )
                } else {
                    SmsUploadWorker.enqueue(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 兜底。这个 `try` 原先**只有 finally、没有 catch**，而这个 scope 既不是
                // SupervisorJob 也没有 CoroutineExceptionHandler —— 上面任何一句抛出异常
                // （号码解析、过滤器、入库之后的那些调用）都会冒泡出去变成**进程崩溃**。
                // 一台无人值守的网关崩了没人知道，而且这条短信连队列行都没留下。
                //
                // 走到这里说明这条短信没能进队列（入库那一段自己有 catch，不会到这儿），
                // 所以记成 SMS_ENQUEUE_FAILED 是准确的。
                Log.e(TAG, "Failed to handle incoming SMS", e)
                EventLog.writeNow(
                    context, EventLog.SMS_ENQUEUE_FAILED, EventLog.LEVEL_ERROR,
                    reason = "处理短信时异常：${e.javaClass.simpleName}"
                )
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
        // 这个 extra 未必真是 subId：它没有公开 API 定义，历史 ROM 往里放的是卡槽号
        // 之类的别的值。直接拿去查 SubscriptionManager，轻则查不到，重则**恰好撞上
        // 另一张卡的 subId** —— 那会把这条短信标成另一张卡的号码。先验一次。
        val subId = subscriptionId.takeIf { DevicePhone.isKnownSubscription(context, it) } ?: -1

        if (subId >= 0) {
            DevicePhone.readForSubscription(context, subId)?.let { return it }
        }

        val storedSubId = DevicePrefs.phoneSubId(context)

        if (subId < 0) {
            // 不知道这条来自哪张卡（部分 ROM 就是不塞那个 extra）。能不能拿配置号码顶上？
            //
            // **只有单卡机能。** 原先是无条件回落，于是双卡机上第二张卡收到的验证码
            // 会被标成第一张卡的号码 —— 服务端按号码缓存，等第一张卡的调用方于是拿到了
            // 第二张卡的码。而「等一串永远不来的码」至少会超时，拿到错码却毫无信号。
            // 单卡机没有第二张卡能收，配置的号码必然是它，回落是安全的。
            //
            // 「查不到卡列表」必须和「只有一张卡」分开：未授权、或 ROM 挡掉卡列表时，
            // querySlots 会返回空列表或退化成一个「默认卡」，两者 size 都 <= 1，
            // 只看 size 会把守不住的回落又放回来。判据本身收在 DevicePhone.isSingleSim 里
            // —— 它在拿不到卡列表时会退回「卡槽数」，那是个不需要电话权限的事实。
            //
            // 退回卡槽数救的是原先最冤的一批设备：单卡机 + 用户拒了电话权限。
            // 那时 isKnownSubscription 恒为 false，subId 恒为 -1，querySlots 带着
            // 「未授予电话权限」的 problem 回来，于是 singleSim 恒为 false、
            // 这个函数恒返回空串 —— 用户手填的号码永远用不上，每条验证码都以
            // phone="" 上传，服务端跳过 sms:code:{号码} 缓存，按号码等码的调用方全部超时，
            // 而设备侧显示的却是「上传成功」。
            return if (DevicePhone.isSingleSim(context)) DevicePrefs.phone(context) else ""
        }

        // 知道来自哪张卡、但卡里没写号码（多数运营商如此，是常态）。
        //
        // 明知与配置号码不是同一张卡 → 留空，绝不能用设备上存的号码顶上。
        if (storedSubId >= 0 && subId != storedSubId) return ""

        // 其余回落到配置号码。这里**刻意不像上面那样保守**：配置号码是用户自己填的
        // （多数卡读不出号码，手填是文档承认的常态），此时 phoneSubId 会是 -1，
        // 光凭它无法判定归属。若就此一律留空，那台设备的验证码永远进不了按号码的缓存，
        // 按号码等码的调用方**每一条都会超时** —— 那是功能全废，比偶发标错更糟。
        return DevicePrefs.phone(context)
    }
}
