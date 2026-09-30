package com.smsgateway.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对账腿：把 `SMS_RECEIVED` 广播**漏投**的短信从系统短信库里补采回来。
 *
 * ## 为什么需要它
 *
 * 2026-09-30 现场：一条【腾讯科技】腾讯视频验证码在系统收件箱里（`_id=225`）、
 * 而 app 队列为空、事件表零记录 —— [com.smsgateway.app.receiver.SmsReceiver]
 * **一次都没被调用**。同时进程健康（连跑 6 天没重启）、心跳正常、不在离线状态，
 * 且同日一条**逐字同构**的验证码正常上传。即：这台 ROM 给三方 app 投递
 * `SMS_RECEIVED` 是不可靠的，偶发漏投。同类事件 09-23 已发生过一次。
 *
 * 只靠广播就等于把网关的可靠性押在一个不可控的 ROM 行为上。本类给它加一条兜底：
 * 短信反正已经落在系统库里了，读出来补一遍就行。
 *
 * ## 两条触发路
 *
 * 1. **ContentObserver**（[start]）—— 系统短信库一有写入就通知，秒级。
 *    验证码有效期只有 60 秒，只有它能把握住这个窗口。
 * 2. **心跳轮**（[reconcile]）—— 网关在跑就一定会到，30 秒一次。
 *    它是「观察者没注册上 / 没收到通知 / 对账腿刚起来」的保险，不依赖任何回调。
 *
 * ## 水位线
 *
 * 用系统库的 `_id`（单调递增）当增量游标，存在 [DevicePrefs.inboxWatermarkId]。
 * 用 `_id` 而不是时间戳：系统改时间（NTP 校时、用户手改、换时区）会让「按时间取增量」
 * 漏掉或重取一段，而 `_id` 不受影响。
 *
 * **首次启用不回溯**：水位线初始化成当下的最大值，历史短信一条都不补 ——
 * 那些验证码早就过期了，补上去只是给服务端添噪音、让「今日短信」这类统计失真。
 *
 * ## 幂等
 *
 * 补采**不做去重判断**，直接交给 [SmsIngest] 走入库：`localMessageId` 上有唯一索引，
 * 广播已经收过的短信会撞索引被静默忽略。于是「跑两遍」与「跑一遍」结果相同 ——
 * 这也是水位线可以「处理完一行才推进」的原因：中途失败就重来，重来是安全的。
 */
object SmsInboxReconciler {

    private const val TAG = "SmsInboxReconciler"

    /**
     * 收件箱里 `sub_id` 这一列的名字。
     *
     * 用字面量而不是 `Telephony.Sms.SUBSCRIPTION_ID`：那个常量来自
     * `TextBasedSmsColumns`，各 compileSdk 上暴露与否有过反复，而 `sub_id` 是
     * provider 的公开列名、十几年没变过。这里要的是一个字符串，不值得为它赌一次编译。
     */
    private const val COLUMN_SUB_ID = "sub_id"

    private val PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        COLUMN_SUB_ID
    )

    /**
     * 并发闸。
     *
     * 两条触发路（观察者与心跳轮）会撞在一起。用 `withLock` 而不是「已在跑就跳过」：
     * 跳过会让**正好在查库那一刻落库的那条短信**被漏掉，而那正是我们要抓的东西。
     * 排队等一轮只是慢几十毫秒，漏一条是永久丢一个验证码。等待方是协程，不占线程。
     */
    private val gate = Mutex()

    /**
     * 落库后多久才允许对账腿碰这条短信。
     *
     * **这是防「抢在广播前面入库」的。** 短信进系统库（provider 写入）与
     * `SMS_RECEIVED` 广播被派发是**两个动作，先后没有保证** —— 如果 provider 先写、
     * 系统后发广播，观察者（实测 200 毫秒内就会醒）会抢在广播前面把这条短信入库，
     * 而广播随后拿的是**另一个键**（它用 PDU 的 SCTS，对账腿用 provider 的 `date`，
     * 见 [SmsIngest] 里的说明），唯一索引拦不住 —— 同一条短信入两行、传两遍，
     * 还会在事件表里同时留下「广播漏采·对账补回」和「已入库」两条自相矛盾的记录。
     *
     * 等 5 秒之后广播早就到了（没到就是真的没到），内容判重于是能正常工作。
     * 代价是补采晚 5 秒 —— 相对验证码 60 秒的有效期可以忽略，而这个方向的取舍
     * 不对称：晚 5 秒只是慢，抢跑一次是重复上传。
     */
    internal const val RECONCILE_MIN_AGE_MS = 5_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 观察者实例。持有它是为了 [stop] 时能注销 —— 不注销会泄漏，且多次 start 会叠加。 */
    private var observer: ContentObserver? = null

    /** 观察者当前的主人（那个 `GatewayForegroundService` 实例）。见 [stop]。 */
    private var owner: Any? = null

    @Volatile
    private var reportedUnavailable = false

    /**
     * 注册观察者并立刻对一次账。由 `GatewayForegroundService.onCreate` 调用。
     *
     * 幂等：观察者已经注册过就只换主人（服务被系统重建时 onCreate 会再跑一遍，
     * 而那时旧实例可能还没销毁）。
     */
    @Synchronized
    fun start(owner: Any, context: Context) {
        val app = context.applicationContext
        this.owner = owner

        if (observer == null) {
            // 观察者注册失败时**不返回** —— 下面那轮对账与水位线初始化是独立的，
            // 而它们在「观察者不可用」时恰恰更重要（只剩心跳那条路兜着）。
            registerObserver(app)?.let { observer = it }
        }

        // 立刻跑一轮，不等第一个 onChange：观察者只看得到**注册之后**的变化，
        // 而网关停着的那段时间里收到的短信正是最需要补的一批。
        requestReconcile(app)
    }

    /**
     * 注销观察者。由 `GatewayForegroundService.onDestroy` 调用。
     *
     * **必须比对主人**：系统重建服务时，旧实例的 `onDestroy` 可能迟到到新实例的
     * `onCreate` 之后 —— 那一刀下去会把新实例刚注册的观察者一起注销，此后对账腿
     * 静默瘫掉（界面上完全看不出来）。与 `GatewayForegroundService.liveInstance`
     * 防的是同一类事。
     */
    @Synchronized
    fun stop(owner: Any, context: Context) {
        if (this.owner !== owner) return
        this.owner = null

        val current = observer ?: return
        observer = null
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(current)
        } catch (e: Exception) {
            Log.w(TAG, "注销短信库观察者失败", e)
        }
    }

    /** fire-and-forget 版本，给回调（ContentObserver）用。 */
    fun requestReconcile(context: Context) {
        val app = context.applicationContext
        scope.launch { reconcile(app) }
    }

    /**
     * 对一轮账：把水位线之后的收件箱短信逐条送进 [SmsIngest]。
     *
     * 整段吞异常。这条腿是**兜底**，它出任何问题都不该影响心跳与上传 ——
     * 与 `SmsUploadWorker.pruneOldUploads` 是同一条取舍。
     */
    suspend fun reconcile(context: Context) {
        val app = context.applicationContext
        gate.withLock {
            try {
                reconcileLocked(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "对账失败", e)
            }
        }
    }

    private suspend fun reconcileLocked(context: Context) {
        if (!hasReadSms(context)) {
            // 没权限就整条腿是断的，而它与「一条都没漏」在界面上长得一模一样 ——
            // 进程内留一条痕，见 EventLog.SMS_RECONCILE_UNAVAILABLE。
            reportUnavailableOnce(context)
            return
        }

        // 权限拿到了就把「已报过」清掉，而不是一次置位管终身。
        //
        // 这个进程连着跑好几天是常态（实测连着 6 天没重启），而权限是可以在运行期间
        // 被用户撤销的（去系统设置里关掉、或某些 ROM 的省电策略代劳）。标志不复位的话，
        // 撤销之后对账腿静默失效，事件表里连一条都没有 —— 正是这次改动要消灭的那种
        // 「看不出哪里坏了」。
        reportedUnavailable = false

        var watermark = DevicePrefs.inboxWatermarkId(context)
        if (watermark < 0L) {
            // 首次：只记下当下收件箱的最大 `_id`，**本次不处理任何行**。
            // 空收件箱取到 null 时写成 0 —— 有「未初始化(-1)」与「初始化成 0」之分，
            // 才不会每轮都重来一次初始化（那会让「初始化后、第一轮查询前」到达的
            // 那条短信被当成历史跳过）。
            val maxId = queryMaxInboxId(context) ?: 0L
            DevicePrefs.setInboxWatermarkId(context, maxId)
            DevicePrefs.setLastReconcileAt(context, System.currentTimeMillis())
            Log.i(TAG, "对账腿首次启用：水位线初始化为 _id=$maxId（不回溯历史）")
            return
        }

        val rows = queryInboxAfter(context, watermark)
        val now = System.currentTimeMillis()
        var checked = 0
        var deferMillis = -1L

        for (row in rows) {
            // 太新的一行**先不碰**，见 [RECONCILE_MIN_AGE_MS]。
            // 行按 `_id` 升序，遇到第一条太新的就收工 —— 它后面的只会更新。
            val defer = reconcileDeferMillis(row.receiveTime, now)
            if (defer > 0) {
                deferMillis = defer
                break
            }

            SmsIngest.ingest(
                context = context,
                sender = row.sender,
                body = row.body,
                receiveTime = row.receiveTime,
                subscriptionId = row.subscriptionId,
                source = SmsIngest.Source.RECONCILED
            )
            checked++

            // **处理完一行才推进水位线**，而不是整批跑完再推：中途中断（进程被杀、
            // 协程取消）就停在上一行，下一轮从那里重来。重来会让已处理的行被重看，
            // 但 [SmsIngest] 的内容判重会把它们静默跳过 —— 「至多一次」换成
            // 「至少一次」是刻意的，宁可重复投递，不可丢。
            DevicePrefs.setInboxWatermarkId(context, row.id)
        }

        // 措辞刻意停留在「检查了」：这一句**不**代表补采到了东西 ——
        // 广播正常投递时对账腿也会看到同一行（然后内容判重跳过），
        // 写「补采 N 条」会让人误以为 ROM 又漏投了。真正「补到了」的证据只有
        // 事件表里那条 [EventLog.SMS_RECOVERED]。
        if (checked > 0) {
            Log.i(TAG, "对账腿检查了 $checked 条（水位线 $watermark → ${DevicePrefs.inboxWatermarkId(context)}）")
        }

        DevicePrefs.setLastReconcileAt(context, now)

        if (deferMillis > 0) {
            // 到点了自己回来一趟，**不干等下一个 30 秒心跳** —— 验证码只有 60 秒有效期，
            // 而这一刻正是「广播可能还在路上」的那几百毫秒，错过这一轮就是半分钟。
            scope.launch {
                delay(deferMillis + 200)
                reconcile(context)
            }
        }
    }

    /**
     * 这条短信现在能不能碰。[receiveTime] 是 provider 给的落库时刻（墙上时间）。
     *
     * @return 还需要等多少毫秒；**0 表示现在就能处理**。见 [RECONCILE_MIN_AGE_MS]。
     *
     * 抽成纯函数是为了能在单测里钉住「等多久」这个口径 —— 抢跑与漏采的边界全在它身上。
     */
    internal fun reconcileDeferMillis(receiveTime: Long, nowMillis: Long): Long {
        val age = nowMillis - receiveTime
        // age < 0（系统时间被往回拨过）不在这里挡：那会让水位线永远推不动，
        // 整条对账腿静默瘫掉。宁可处理一条时间怪异的短信，也不能让管道堵死。
        return if (age in 0 until RECONCILE_MIN_AGE_MS) RECONCILE_MIN_AGE_MS - age else 0L
    }

    // ------------------------------------------------------------------ 数据访问

    /**
     * 收件箱里 `_id` 大于水位线的短信，按 `_id` 升序。
     *
     * 顺序必须与水位线的推进方向一致：推进写的是「最后处理完那一行的 id」，
     * 乱序会让中间那些行被跳过。
     */
    private fun queryInboxAfter(context: Context, watermark: Long): List<SmsInboxRow> {
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                PROJECTION,
                "${Telephony.Sms._ID} > ?",
                arrayOf(watermark.toString()),
                "${Telephony.Sms._ID} ASC"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "查收件箱被拒（缺权限？）", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "查收件箱失败", e)
            null
        } ?: return emptyList()

        val now = System.currentTimeMillis()

        return cursor.use { c ->
            val idIdx = c.getColumnIndex(Telephony.Sms._ID)
            val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
            val subIdx = c.getColumnIndex(COLUMN_SUB_ID)

            // 列取不到（ROM 的 provider 少给了一列）时返回空列表，而不是让
            // getColumnIndexOrThrow 抛出去 —— 那会让整条腿在异常里静默瘫掉。
            // 水位线留在原地，下一轮还会重试。
            if (idIdx < 0 || addrIdx < 0 || bodyIdx < 0 || dateIdx < 0) {
                Log.w(TAG, "收件箱缺少预期的列，本轮跳过")
                return@use emptyList()
            }

            buildList {
                while (c.moveToNext()) {
                    add(
                        normalizeInboxRow(
                            id = c.getLong(idIdx),
                            address = c.getString(addrIdx),
                            body = c.getString(bodyIdx),
                            date = c.getLong(dateIdx),
                            // 列不存在或为 NULL 都按「不知道来自哪张卡」处理（null → -1）。
                            subId = if (subIdx < 0 || c.isNull(subIdx)) null else c.getInt(subIdx),
                            nowMillis = now
                        )
                    )
                }
            }
        }
    }

    /** 收件箱里最大的 `_id`；空收件箱返回 null。 */
    private fun queryMaxInboxId(context: Context): Long? {
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null, null,
                "${Telephony.Sms._ID} DESC"
            )
        } catch (e: Exception) {
            Log.w(TAG, "查收件箱最大 _id 失败", e)
            null
        } ?: return null

        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    // ------------------------------------------------------------------ 观察者与权限

    private fun registerObserver(context: Context): ContentObserver? {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // 库变了就补一轮。**不做去抖**：provider 只在收到短信时写入，
                // 一轮对账通常查 0 行，成本可以忽略；而去抖会引入「抖到最后一刻才跑」
                // 的延迟，验证码等不起。
                requestReconcile(context)
            }
        }

        return try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                // notifyForDescendants：收件箱是 `content://sms` 下的子路径，
                // 不设它就收不到 `content://sms/inbox` 的写入通知。
                true,
                observer
            )
            observer
        } catch (e: Exception) {
            Log.w(TAG, "注册短信库观察者失败，退化为心跳轮对账", e)
            null
        }
    }

    private fun hasReadSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 对账腿没在跑时留一条痕，进程内只报一次。
     *
     * 与 [com.smsgateway.app.receiver.SmsReceiver] 的 reportedUndecodable、
     * [SmsIngest] 的 reportedNoPhone 是同一套写法：这条路每轮都会命中，
     * 逐次记会把 7 天的事件表刷成同一条。
     */
    private suspend fun reportUnavailableOnce(context: Context) {
        if (reportedUnavailable) return
        reportedUnavailable = true
        EventLog.writeNow(
            context, EventLog.SMS_RECONCILE_UNAVAILABLE, EventLog.LEVEL_WARN,
            reason = "未授予「读取短信」权限，广播漏投时无法补采"
        )
    }
}

/**
 * 一行收件箱记录，字段与 [SmsIngest.ingest] 的入参一一对应。
 *
 * [id] 是水位线用的游标，**不参与** [LocalMessageId] 的计算。
 */
internal data class SmsInboxRow(
    val id: Long,
    val sender: String,
    val body: String,
    val receiveTime: Long,
    val subscriptionId: Int
)

/**
 * 把 provider 读出来的原始值归一成 [SmsIngest] 的入参。
 *
 * 抽成纯函数（不碰 Cursor、不碰 Context）是为了能在 JVM 单测里钉住这套口径 ——
 * 它决定了同一条短信在**广播路径**与**对账路径**下是否算出同一个
 * [LocalMessageId]。任何一处不齐，同一条短信就会入两行、被传两次：
 *
 * - `address` / `body` 为 NULL → 空串。与广播路径的 `?: ""` 同一口径。
 * - `date` 为 0 → 当前时刻。键里的「收到时刻」取到 0 会让键退化成
 *   `sms-0-<卡槽>-<哈希>`，同一号码连发两条同样内容的短信就会撞键、第二条被丢。
 *   与 `SmsReceiver` 里那句 `?: System.currentTimeMillis()` 是同一个理由。
 * - `sub_id` 为 NULL → -1。与广播路径取不到 `subscription` extra 时同一个值，
 *   语义都是「不知道来自哪张卡」，交给 [SmsIngest] 的号码归属逻辑去处理。
 *
 * @param nowMillis 只在 `date` 不可用时兜底。由调用方传进来而不是在这里读时钟，
 *   这样单测能给出确定值。
 */
internal fun normalizeInboxRow(
    id: Long,
    address: String?,
    body: String?,
    date: Long,
    subId: Int?,
    nowMillis: Long
): SmsInboxRow = SmsInboxRow(
    id = id,
    sender = address.orEmpty(),
    body = body.orEmpty(),
    receiveTime = date.takeIf { it > 0 } ?: nowMillis,
    subscriptionId = subId ?: -1
)
