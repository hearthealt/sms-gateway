package com.smsgateway.app.parser

/**
 * 短信采集过滤。
 *
 * 规则写死在这里，不做成可配置项：网关的职责就是只捞验证码短信，
 * 把开关暴露出去只会多一层没人会去调的配置。
 */
object SmsFilter {

    private val includeKeywords = listOf("验证码", "校验码", "动态码", "verification code")
    private val excludeKeywords = listOf("余额", "交易", "支付", "账单", "bank")

    fun shouldCollect(sender: String, body: String): Boolean {
        val text = "$sender $body".lowercase()
        if (excludeKeywords.any { text.contains(it.lowercase()) }) return false
        return includeKeywords.any { text.contains(it.lowercase()) }
    }
}
