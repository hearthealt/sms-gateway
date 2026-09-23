package com.smsgateway.app.util

import android.content.Context
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.EventLogEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 重要事件的运行记录，落本地库、保留 7 天、界面上可看。
 *
 * 这是排查「短信为什么没转发」的唯一线索来源。原先一条验证码短信静默丢失时，
 * 服务端和本地队列都查不到，而 [com.smsgateway.app.receiver.SmsReceiver] 的
 * 失败分支全都只 `return` 或只打 `Log.e` —— 这台设备是无人值守跑的，
 * 日志要人在场才抓得到（而且 logcat 缓冲区只有几分钟）。所以宁可多写一张表。
 *
 * ### 三条硬约束
 *
 * 1. **写入永远不能打断正事。** 每个方法自己 try/catch 全部异常 —— 记日志失败
 *    绝不能让一条短信采集不到、或一次上传发不出去。这与
 *    [com.smsgateway.app.worker.SmsUploadWorker] 里 `pruneOldUploads` 的哲学一致。
 * 2. **[reason] 只允许受控文案**（HTTP 状态码、固定短语、异常类名）。绝不拼接
 *    `response.errorBody()` 之类的外部内容 —— 那是把不可控的字节流写进一张长期留存的表。
 * 3. **绝不写短信正文、验证码、deviceToken、enrollSecret。** 见 [EventLogEntity] 的说明。
 *
 * ### 什么算「重要」
 *
 * 判断标准是：**事后排查这件事时，没有它我会不会卡住。** 按这个标准，
 * 常规心跳（30 秒一条，7 天两万条，还会把真事件淹没）、每一次上传重试轮转
 * （队列行上本来就有 retryCount）、界面操作、网络状态变化，都不记。
 */
object EventLog {

    private const val TAG = "EventLog"

    /** 保留天数。与 SmsUploadWorker 的已上传记录保留期一致。 */
    const val RETENTION_DAYS = 7L

    /**
     * [reason] 的长度上限。
     *
     * 受控文案本来都很短，这个上限是给「异常类名 + 一句 message」这类兜底留的余量，
     * 同时保证就算哪天有人不慎拼了长字符串进来，也不会把整行撑坏。
     */
    private const val MAX_REASON_LENGTH = 200

    // ── 级别 ────────────────────────────────────────────────────────────────
    // 与 EventLogEntity.level 同源；界面据此上色（error 红 / warn 橙 / info 灰）。

    const val LEVEL_INFO = "info"
    const val LEVEL_WARN = "warn"
    const val LEVEL_ERROR = "error"

    // ── 短信采集链路 ────────────────────────────────────────────────────────

    /** 广播收到了，但正文没命中采集关键词，按设计丢弃。 */
    const val SMS_FILTERED = "sms_filtered"

    /**
     * 广播收到了、关键词命中了，但解析不出验证码，按设计丢弃。
     *
     * **已不再写入**（2026-09-23）：客户端不再解析验证码，「认不出来就丢」这道闸门
     * 已经拆掉——关键在于它曾是最贵的一条丢失路径（服务端根本看不到那条短信）。
     * 常量与下面的中文标签保留着，只因为升级前入库的旧行还带着这个 type，
     * 删掉的话日志页会显示原始英文常量名。
     */
    const val SMS_NO_CODE = "sms_no_code"

    /** 已入库，等待上传。**这是正常路径的锚点。** */
    const val SMS_ENQUEUED = "sms_enqueued"

    /** 唯一索引冲突：`insert` 返回 -1。同一条短信重投时会出现，属正常。 */
    const val SMS_DUPLICATE = "sms_duplicate"

    /** 入库失败（异常）。**这一类是真正的丢失，见到就该报警。** */
    const val SMS_ENQUEUE_FAILED = "sms_enqueue_failed"

    /** 已入库，但按当前策略不上传（未注册 / 被禁用 / 网关已停止）。不是丢失。 */
    const val SMS_HELD = "sms_held"

    /**
     * 短信能正常上传，但**读不到收信的那张卡的号码**。
     *
     * 这不是上传失败 —— 服务端收得下（phone 允许为空），只是会跳过写
     * `sms:code:{号码}` 验证码缓存，于是按号码等码的调用方**永远等不到**，
     * 而设备侧记的是「上传成功」、队列里那行也正常消失。三处都没有异常信号，
     * 这条事件是唯一把它变成可见的地方。
     *
     * 进程内只记一条（见 SmsReceiver）：号码没配的设备每一条短信都会命中，
     * 逐条记会把 7 天的事件表刷成同一条。
     */
    const val SMS_NO_PHONE = "sms_no_phone"

    // ── 上传 ────────────────────────────────────────────────────────────────

    const val UPLOAD_OK = "upload_ok"

    /** 该行**首次**上传失败。后续重试不再重复记，否则一条卡住的短信会刷满整页。 */
    const val UPLOAD_RETRYING = "upload_retrying"

    /** 服务端明确拒绝（400/422），该行标终态 failed，不会再重试。 */
    const val UPLOAD_REJECTED = "upload_rejected"

    /** 401：令牌失效，已停止上报直到重新注册。 */
    const val UPLOAD_TOKEN_REJECTED = "upload_token_rejected"

    /** 403：设备被管理员禁用。 */
    const val UPLOAD_DEVICE_DISABLED = "upload_device_disabled"

    /** 单轮达到最大尝试次数收手。放弃的是这一轮，行仍是 pending。 */
    const val UPLOAD_ROUND_GAVE_UP = "upload_round_gave_up"

    // ── 设备身份与状态 ──────────────────────────────────────────────────────

    const val DEVICE_REGISTERED = "device_registered"
    const val DEVICE_REGISTER_FAILED = "device_register_failed"
    const val DEVICE_TOKEN_REJECTED = "device_token_rejected"
    const val DEVICE_DISABLED = "device_disabled"
    const val DEVICE_ENABLED = "device_enabled"

    // ── 心跳 ────────────────────────────────────────────────────────────────

    /** **连续**失败达到阈值时才记一条，不是每次失败都记。 */
    const val HEARTBEAT_FAILED = "heartbeat_failed"
    const val HEARTBEAT_RECOVERED = "heartbeat_recovered"

    // ── 网关服务 ────────────────────────────────────────────────────────────

    /**
     * 进程启动。**每次冷启动一条**，写在 `Application.onCreate`。
     *
     * 与 [GATEWAY_STARTED] 是两件事，合起来才能回答「这台机器刚才到底发生了什么」：
     *
     * - 只有 [APP_STARTED]：有人打开了应用（或系统为了一条广播把进程拉起来），而网关没起
     * - 只有 [GATEWAY_STARTED]：服务被系统重建（START_STICKY / 开机），进程是它带起来的
     * - 两条紧挨着：用户点了启动，或开机自启正常
     * - 两条都没有，但队列在动：进程一直活着，没重启过
     *
     * 排查「网关莫名其妙停了」时，这一条是唯一能把「被系统杀了」和「压根没起来」
     * 分开的证据 —— 前者会看到一串 APP_STARTED，后者一条都没有。
     */
    const val APP_STARTED = "app_started"

    const val GATEWAY_STARTED = "gateway_started"
    const val GATEWAY_STOPPED = "gateway_stopped"

    /** 服务被系统销毁（不是用户主动停）。 */
    const val GATEWAY_DESTROYED = "gateway_destroyed"

    /**
     * 界面上的中文标签。放在常量旁边而不是界面层：两处各写一份的话，
     * 加一个新事件类型时总有一边会被忘掉，而那一边的表现是日志页上冒出一串英文常量名。
     *
     * 兜底返回 `type` 原文 —— 认不出来的类型原样显示，总比显示空白强，
     * 而且一眼就能看出是新类型没登记。
     */
    fun labelOf(type: String): String = when (type) {
        SMS_FILTERED -> "已过滤"
        SMS_NO_CODE -> "无验证码"
        SMS_ENQUEUED -> "已入库"
        SMS_DUPLICATE -> "重复短信"
        SMS_ENQUEUE_FAILED -> "入库失败"
        SMS_HELD -> "暂不上传"
        SMS_NO_PHONE -> "号码未知"
        UPLOAD_OK -> "上传成功"
        UPLOAD_RETRYING -> "上传失败"
        UPLOAD_REJECTED -> "被服务端拒绝"
        UPLOAD_TOKEN_REJECTED -> "上传被拒（令牌）"
        UPLOAD_DEVICE_DISABLED -> "上传被拒（已禁用）"
        UPLOAD_ROUND_GAVE_UP -> "本轮放弃"
        DEVICE_REGISTERED -> "注册成功"
        DEVICE_REGISTER_FAILED -> "注册失败"
        DEVICE_TOKEN_REJECTED -> "令牌失效"
        DEVICE_DISABLED -> "被管理员禁用"
        DEVICE_ENABLED -> "已恢复启用"
        HEARTBEAT_FAILED -> "心跳中断"
        HEARTBEAT_RECOVERED -> "心跳恢复"
        APP_STARTED -> "进程启动"
        GATEWAY_STARTED -> "网关启动"
        GATEWAY_STOPPED -> "网关停止"
        GATEWAY_DESTROYED -> "网关被系统销毁"
        else -> type
    }

    /**
     * 关键路径用这个：**写完才返回**。
     *
     * 「关键路径」指的是 `SmsReceiver` 那种 hold 着 `goAsync()` 的 `pendingResult`
     * 的场合 —— 协程一旦提前返回、广播就被放掉，进程随时可能被回收，用 [write]
     * 那种 fire-and-forget 会把刚发生的事件一起带走。那条路径上「记下来」本身就是目的，
     * 值得多等一次插入。
     */
    suspend fun writeNow(
        context: Context,
        type: String,
        level: String,
        sender: String? = null,
        phone: String? = null,
        reason: String? = null,
        smsId: Long? = null
    ) {
        try {
            insert(context, type, level, sender, phone, reason, smsId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 记日志失败绝不能影响调用方 —— 调用方往往正在处理一条真实的短信。
            Log.w(TAG, "Failed to record event $type", e)
        }
    }

    /**
     * 没有协程上下文的地方用这个（服务生命周期回调、prefs 状态跃迁等）。
     * 自建 IO 作用域，调用方不阻塞。
     */
    fun write(
        context: Context,
        type: String,
        level: String,
        sender: String? = null,
        phone: String? = null,
        reason: String? = null,
        smsId: Long? = null
    ) {
        val app = context.applicationContext
        writeScope.launch {
            writeNow(app, type, level, sender, phone, reason, smsId)
        }
    }

    /**
     * 剪枝：删掉超过 [RETENTION_DAYS] 天的事件。返回删除行数。
     *
     * 与 `SmsUploadWorker.pruneOldUploads` 同一套写法与理由：清库失败不该影响上报，
     * 所以整段吞异常只记日志。挂载点有两处 —— 上传 worker（有短信就会跑）与
     * 前台服务的心跳循环（网关在跑就一定会到），两者互为兜底。
     */
    suspend fun prune(context: Context): Int {
        return try {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
            AppDatabase.getInstance(context).eventLogDao().deleteOldRecords(cutoff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Prune event log failed", e)
            0
        }
    }

    /** fire-and-forget 用一个进程级作用域：Worker / Receiver / Service 都可能调进来。 */
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private suspend fun insert(
        context: Context,
        type: String,
        level: String,
        sender: String?,
        phone: String?,
        reason: String?,
        smsId: Long?
    ) {
        AppDatabase.getInstance(context).eventLogDao().insert(
            EventLogEntity(
                type = type,
                level = level,
                // sender/phone 都是号码类元数据，不含正文，可以留；
                // 空串归一成 null，免得界面上是个空位却看起来像「有值但读不出来」
                sender = sender?.takeIf { it.isNotBlank() },
                phone = phone?.takeIf { it.isNotBlank() },
                reason = reason?.take(MAX_REASON_LENGTH),
                smsId = smsId,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
