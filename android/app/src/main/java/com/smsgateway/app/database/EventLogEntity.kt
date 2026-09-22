package com.smsgateway.app.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 重要事件运行记录。
 *
 * **存在的理由**：一条验证码短信曾经静默丢失 —— 服务端没有、本地待上传队列也没有，
 * 而 `SmsReceiver` 的几条失败分支（过滤未命中、解析不出验证码、入库异常、
 * 唯一索引冲突）全都只 `return` 或只打日志，事后完全无法定位是哪一条。
 * 这张表就是那些分支的落点：**丢可以，但不能丢得无声无息**。
 *
 * **只记重要的**。这不是把 logcat 抄一份 —— 常规心跳（30 秒一条，7 天两万条）、
 * 每一次上传重试轮转、界面操作、网络状态变化都不进。判断标准是「事后排查这件事时，
 * 没有它我会不会卡住」。写入侧的类型清单见 `util/EventLog`。
 *
 * **绝不写短信正文、验证码明文、deviceToken、enrollSecret。**
 * 这条与 `network/RetrofitClient` 里「只在 debug 包打正文」、以及
 * `AndroidManifest` 的 `allowBackup="false"` 是同一条原则：
 * 明文不能留在任何生命周期比它长的地方。要正文请按 [smsId] 回 `sms_queue` 查。
 *
 * [smsId] 存的是 `sms_queue.id`（本地自增主键），**不是** `localMessageId` ——
 * 后者由「发送方 + 接收时刻 + 正文哈希」拼成，把正文哈希写进一张保留 7 天的表里
 * 等于换一种方式留痕。自增 id 本身不含任何信息。
 */
@Entity(
    tableName = "event_log",
    // 只按时间建索引：查询与剪枝都是「按时间倒序取 N 条」和「删早于 X 的」。
    // 7 天的事件量在几百条量级，复合索引在这里没有任何意义，
    // 而每多一条 DDL 就多一次写错迁移的机会。
    indices = [Index(value = ["createdAt"])]
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 事件类型，取值见 `util/EventLog` 里的常量。 */
    val type: String,

    /** "info" / "warn" / "error"，决定界面上徽标的颜色。 */
    val level: String,

    /** 短信发送方。与 `sms_queue.sender` 同源，不含正文。 */
    val sender: String? = null,

    /**
     * 这条短信是**哪个号**收到的。与 `sms_queue.phone` 同源。
     *
     * 多卡设备上这是「为什么这条没转发」的关键一维：两张卡各自在收，
     * 而调用方等的验证码是按号码缓存与匹配的 —— 不知道是哪个号收到的，
     * 就分不清「这张卡没收到」和「收到了但标错了号码」。
     *
     * 对 `sms_filtered` / `sms_no_code` 这两种**没有队列行**的事件尤其重要：
     * 那时它是这条短信唯一留下的归属信息。
     */
    val phone: String? = null,

    /** 判定结果 / 原因码 / HTTP 状态。**只允许受控文案**，见 `util/EventLog.write`。 */
    val reason: String? = null,

    /** 关联的本地队列行，可空。 */
    val smsId: Long? = null,

    /** epoch 毫秒。与 `sms_queue.receiveTime` 同一套时间基准。 */
    val createdAt: Long
)
