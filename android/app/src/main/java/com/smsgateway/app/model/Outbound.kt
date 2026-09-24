package com.smsgateway.app.model

/**
 * 服务端交给本机去发的一条短信。
 *
 * 与 [CommandPayload] 同走心跳下发，但**语义完全不同**：指令重发的代价是「可能多做一次」，
 * 而短信重发的代价是真的又发一条出去、又计费一次。所以服务端只下发一次，
 * 回执丢了就记成「结果未知」，不靠重发去确认。
 */
data class OutboundPayload(
    val id: Long,
    /** 幂等键：回执时原样带回，服务端靠它认领。 */
    val key: String,
    val phone: String,
    val content: String,
    /** 指定卡槽（subscriptionId）；null = 选主卡。控制台不提供这个字段（它不知道设备有几张卡）。 */
    val simSlot: Int? = null
)

/**
 * 一条外发短信的执行结果。**随下一次心跳上报**，不单开接口 ——
 * 这条短信已经离开手机了，回执晚几秒完全无所谓。
 *
 * @param segments 实际拆成了几段 —— **直接等于计费条数**，只有设备知道它那张卡
 *   走的是 GSM-7 还是 UCS-2（中文 67 字/段、英文 153 字/段）。
 */
data class OutboundResult(
    val key: String,
    val status: String,
    val errorReason: String? = null,
    val segments: Int? = null
)

/** 回执状态的字面量。写成常量而不是枚举，与服务端用 String 接收的理由一致。 */
object OutboundStatus {
    /** 已交给无线电（`RESULT_OK`）。注意这**不等于**对方收到了。 */
    const val SENT = "SENT"

    /** 对方已收到（投递回执，多数运营商拿不到，能拿到就算额外收获）。 */
    const val DELIVERED = "DELIVERED"

    const val FAILED = "FAILED"
}
