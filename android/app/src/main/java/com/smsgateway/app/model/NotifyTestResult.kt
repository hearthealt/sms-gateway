package com.smsgateway.app.model

/**
 * 一个转发渠道的测试结果（服务端逐渠道返回）。
 *
 * 全部给默认值：Gson 遇到缺字段时不会因此抛异常（与其它 model 同一套写法）。
 */
data class NotifyTestResult(
    val channelId: Long = 0,
    val channelName: String = "",
    val ok: Boolean = false,
    /** 成功时为空；失败时是给人看的原因。 */
    val message: String? = null
)
