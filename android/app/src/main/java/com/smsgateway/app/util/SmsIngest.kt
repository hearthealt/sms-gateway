package com.smsgateway.app.util

import android.content.Context
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.parser.SmsFilter
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CancellationException

/**
 * 「一条短信进队列」的唯一实现。
 *
 * 有两条路会把短信送到这里，它们**必须**共用这一段：
 *
 * 1. [com.smsgateway.app.receiver.SmsReceiver] —— 收到 `SMS_RECEIVED` 广播（正常路径）；
 * 2. [SmsInboxReconciler] —— 读系统短信库补采广播**漏投**的短信（兜底路径）。
 *
 * 拆出来不是为了少写几行，是为了让两条路**不可能分叉**。这段里有一处只要两边
 * 算得不一样就会出事的逻辑：[LocalMessageId] 的四个入参。它是队列行的唯一索引，
 * 也是服务端的幂等键 —— 广播路径拿的是 PDU 的 `timestampMillis` 与 intent 里的
 * `subscription` extra，对账路径拿的是 provider 的 `date` 与 `sub_id`。两边一旦
 * 产生哪怕 1 毫秒的差异，同一条短信就会入两行、被传两次（服务端按内容哈希判重后
 * 记录仍是一条，但设备侧多一次请求、服务端日志多一条「重复」）。
 *
 * 为什么广播漏投需要兜底：2026-09-30 现场，一条腾讯视频验证码在系统收件箱里、
 * 而 app 队列为空、事件表零记录 —— 广播压根没交到应用，且进程健康、心跳正常。
 * 详见 [SMS_RECOVERED][EventLog.SMS_RECOVERED] 的说明。
 */
object SmsIngest {

    private const val TAG = "SmsIngest"

    /**
     * 这条短信是从哪条路进来的。
     *
     * 它只影响**记录什么事件**，不影响任何业务判断 —— 过滤规则、号码归属、
     * 去重、上传触发两条路完全一致，这正是本类存在的意义。
     */
    enum class Source {
        /** `SMS_RECEIVED` 广播。 */
        BROADCAST,

        /** 对账腿从系统短信库补采。 */
        RECONCILED
    }

    /**
     * 收信号码读不到时是否已经报过。
     *
     * 进程内只报一次：号码没配的设备每一条短信都会命中，逐条记会把 7 天的事件表
     * 刷成同一条。见 [EventLog.SMS_NO_PHONE]。与 HeartbeatSender 里那个
     * failureReported 是同一套写法。
     */
    @Volatile
    private var reportedNoPhone = false

    /**
     * 把一条短信送进本地队列，并按当前状态决定要不要排上传。
     *
     * @param sender 发送方。允许空串（个别 PDU 解出的 originatingAddress 就是 null），
     *   绝不能在这里编一个占位号码 —— 那会让这条短信挂到别人名下。
     * @param receiveTime 收到时刻（毫秒）。广播路径取非 0 的最小 `timestampMillis`，
     *   对账路径取 provider 的 `date`。两者必须指向同一个时刻，理由见类注释。
     * @param subscriptionId 收到它的那张卡。广播路径是 intent 里的 `subscription`
     *   extra（可能取不到，为 -1），对账路径是 provider 的 `sub_id`。
     */
    suspend fun ingest(
        context: Context,
        sender: String,
        body: String,
        receiveTime: Long,
        subscriptionId: Int,
        source: Source
    ) {
        try {
            // 收信卡号只解析一次，下面每一处都用它 —— **包括被过滤掉的那条路径**。
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
            if (!SmsFilter.shouldCollect(sender, body)) {
                // 按设计丢弃，但要留痕：现场「我明明发了验证码却没转发」时，
                // 这一条是唯一能区分「广播根本没到」和「到了但被过滤掉」的证据 ——
                // 两者在别处长得一模一样（队列没有、服务端没有）。
                //
                // **刻意不带 sender**：这一支过滤掉的正是「我们决定不要」的短信，
                // 把它们的发送方号码再留 7 天，与「不入库」这个决定自相矛盾；
                // 而它们又是量最大的一类，扛着号码会把日志页刷成收件箱镜像。
                // 留下收信号码与时刻，足以回答「那一刻到底有没有短信进来」。
                //
                // 对账路径**不记**：那一支本来就只是把库里已有的短信重看一眼，
                // 广播若投到了早就记过了，每轮重记会把日志页刷满。
                if (source == Source.BROADCAST) {
                    EventLog.writeNow(
                        context, EventLog.SMS_FILTERED, EventLog.LEVEL_INFO,
                        phone = phone, reason = "未命中采集关键词"
                    )
                }
                return
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

            // 2.5 对账路径的判重。
            //
            // 广播路径靠 localMessageId 的唯一索引就够：同一条短信重投时它的四个成分
            // 全同。**对账路径不行。** 那个键里的「收到时刻」，广播路径取的是 PDU 的
            // SCTS（短信中心时间戳，只有**秒**精度），对账路径只能取 provider 的
            // `date` —— 实测那是**收信时的墙上时间**（带毫秒），与 SCTS 不是同一个值。
            // 真机数据（2026-09-30，`_id=226`）：`date=…092853` 而 `date_sent=…091000`，
            // 两者都不整秒，谁都不等于 PDU 里的那个 SCTS。
            //
            // 于是同一条短信在两条路下算出的键不同，唯一索引拦不住 —— 广播已经收过的
            // 短信会被对账腿当成新短信再捞一遍、再传一次，还会把
            // [EventLog.SMS_RECOVERED] 刷成「每条都漏采」，把这个信号彻底废掉。
            //
            // 所以对账腿按**正文 + 时间窗**判重，而且**连发送方都不比**。
            //
            // 不比发送方是 2026-09-30 实测后改的：MIUI 会把某些发送方在 provider 里
            // 存成**显示名**而不是号码 —— 同一条 106 短信，广播路径从 PDU 拿到的是
            // `10687534278973838005`，provider 的 `address` 存的却是「深度求索」
            // （`b2c_numbers` 列也是这个名字，没有任何一列留着原始号码）。
            // 带上发送方比，这类短信就永远匹配不上。
            //
            // 这不是把判据放水：服务端的去重键是 `uk_device_source_hash
            // (device_id, source_hash)`，而 `source_hash` 是**正文的 SHA-256** ——
            // 本来就不含发送方。本地按正文判重与服务端语义完全一致，
            // 不会丢掉任何服务端会保留的记录。要捞的验证码每条正文都不同，
            // 误合并的概率可以忽略。
            if (source == Source.RECONCILED) {
                val window = reconcileDedupWindow(receiveTime)
                val alreadyIngested = try {
                    AppDatabase.getInstance(context).smsQueueDao()
                        .countSameContentInWindow(body, window.first, window.last)
                } catch (e: Exception) {
                    // 查不动时**当「没收到过」处理**：查不动就跳过会丢掉一条真正被漏投的
                    // 验证码；查不动就当收过，最坏是重复上传一次 —— 而那个由服务端的
                    // 内容哈希拦下。这个方向的取舍与整个对账腿一致：宁可重复，不可丢。
                    Log.w(TAG, "对账判重查询失败，按未收过处理", e)
                    0
                }
                if (alreadyIngested > 0) {
                    // 广播已经收过这条 —— 这是**稳态**，不是异常。不记事件：每轮对账都会
                    // 把库里已有的短信重看一遍，记了会把日志页刷成收件箱镜像。
                    Log.d(TAG, "对账：内容已入过库，跳过")
                    return
                }
            }

            // 3. Generate unique local message ID
            // 格式与理由见 LocalMessageId：老格式的低 16 位截断会让「同一号码在同一秒
            // 发来的两条不同正文」撞成同一条，第二条被静默丢弃并记成「重复短信」；
            // 而「同一张卡、同一秒、正文相同、发送方不同」那一维此前根本没进键里。
            val localMessageId =
                LocalMessageId.build(receiveTime, subscriptionId, body, sender)

            // 4. Enqueue to Room database
            // deviceId 取本机已注册的信息；未注册时为空串，注册成功后
            // DashboardViewModel 会调 backfillIdentity() 补上并触发上传。
            val entity = SmsQueueEntity(
                localMessageId = localMessageId,
                deviceId = DevicePrefs.deviceId(context),
                phone = phone,
                sender = sender,
                content = body,
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
                    // 广播路径记「已入库」（正常路径的锚点）；对账路径记「广播漏采·对账补回」
                    // —— 后者是这台 ROM 漏投短信的**唯一证据**，见 EventLog.SMS_RECOVERED。
                    EventLog.writeNow(
                        context,
                        if (source == Source.BROADCAST) {
                            EventLog.SMS_ENQUEUED
                        } else {
                            EventLog.SMS_RECOVERED
                        },
                        EventLog.LEVEL_INFO,
                        sender = sender, phone = phone, smsId = rowId
                    )
                } else if (source == Source.BROADCAST) {
                    EventLog.writeNow(
                        context, EventLog.SMS_DUPLICATE, EventLog.LEVEL_WARN,
                        sender = sender, phone = phone,
                        reason = "localMessageId 已存在（短信重投）"
                    )
                } else {
                    // 对账路径撞唯一索引 = **稳态**，不是异常：这条短信广播已经收过了，
                    // 对账腿只是把它重看了一遍（水位线回拨、或对账比广播先跑完）。
                    // 记事件的话，日志页会被刷成收件箱镜像 —— 每轮对账都会把库里
                    // 已有短信全部重看。
                    //
                    // 直接返回，连后面的 enqueue 一起跳过：那一行不是广播路径刚排过
                    // 上传，就是当时按 held 处理过（网关停止/未注册），不该由对账腿
                    // 替它重复点火 —— 对账每 30 秒跑一轮，那会变成每 30 秒排一次上传。
                    return
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
            // 兜底。这个 `try` 原先**只有 finally、没有 catch**（在 SmsReceiver 里），
            // 而那个 scope 既不是 SupervisorJob 也没有 CoroutineExceptionHandler ——
            // 上面任何一句抛出异常（号码解析、过滤器、入库之后的那些调用）都会冒泡
            // 出去变成**进程崩溃**。一台无人值守的网关崩了没人知道，而且这条短信连
            // 队列行都没留下。
            //
            // 走到这里说明这条短信没能进队列（入库那一段自己有 catch，不会到这儿），
            // 所以记成 SMS_ENQUEUE_FAILED 是准确的。
            Log.e(TAG, "Failed to handle incoming SMS", e)
            EventLog.writeNow(
                context, EventLog.SMS_ENQUEUE_FAILED, EventLog.LEVEL_ERROR,
                reason = "处理短信时异常：${e.javaClass.simpleName}"
            )
        }
    }

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

    /**
     * 这条短信该记为哪个号码。
     *
     * 多卡时必须记「收到它的那张卡」的号码，而不是设备上存的那一个 ——
     * 服务端按号码缓存验证码（sms:code:{号码}），标错号码会让等待某张卡验证码的
     * 调用方拿到另一张卡的码。错答案比超时更糟，所以下面宁可留空。
     *
     * 原先这个方法在 SmsReceiver 里；对账腿也要走同一套归属判断（否则同一条短信
     * 两条路算出的 phone 可能不同），所以随入库逻辑一起搬到这里。
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

/**
 * 对账腿判重的时间窗：以 provider 给的那一刻为中心，前后各 [RECONCILE_DEDUP_WINDOW_MS]。
 *
 * 取这么宽是因为**两个时刻的差不是一个固定值**，它包含了短信的实际投递延迟：
 * 广播路径的 SCTS 是短信中心收到它的时刻，provider 的 `date` 是手机收到它的时刻，
 * 中间隔着网络。真机实测差值在 0.4～2 秒，但网络慢时没有上界。
 *
 * 放宽的代价很小：窗口里要撞上「同发送方 + 逐字同正文」，而网关捞的是验证码 ——
 * 每次的码都不同。真撞上了，服务端的内容哈希去重也会把它们并成一条（见
 * `SmsQueueDao.countSameContentInWindow`）。
 *
 * 抽成函数（而不是内联一个 `±常量`）是为了能在单测里钉住这个口径 ——
 * 这条链路上唯一会「一条短信被捞两遍」的风险就集中在这里。
 */
internal fun reconcileDedupWindow(receiveTime: Long): LongRange =
    (receiveTime - RECONCILE_DEDUP_WINDOW_MS)..(receiveTime + RECONCILE_DEDUP_WINDOW_MS)

/** 见 [reconcileDedupWindow]。10 分钟：投递延迟的合理上界，而验证码内容不会重复。 */
internal const val RECONCILE_DEDUP_WINDOW_MS = 10 * 60 * 1000L
