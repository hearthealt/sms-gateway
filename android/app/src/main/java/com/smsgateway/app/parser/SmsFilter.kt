package com.smsgateway.app.parser

/**
 * 短信采集过滤。
 *
 * 规则写死在这里，不做成可配置项：网关的职责就是只捞验证码短信，
 * 把开关暴露出去只会多一层没人会去调的配置。
 */
object SmsFilter {

    private val includeKeywords = listOf("验证码", "校验码", "动态码", "verification code")

    /**
     * 这里原先还有一份排除词表（`余额` / `交易` / `支付` / `账单` / `bank`），
     * 匹配 `"$sender $body"` 全文，并且在任何判断之前先整条否掉。它造成的漏采是致命的：
     *
     * ```
     * 【支付宝】您的验证码是 123456，请勿泄露        → 含「支付」→ 丢弃
     * 【XX银行】验证码 123456，账户余额变动…          → 含「余额」→ 丢弃
     * Bank of China: your verification code is …    → 含「bank」→ 丢弃
     * ```
     *
     * 而这些**恰恰是这个产品要捞的东西**。用户那边在等验证码，网关这边什么都不做，
     * 而且队列里连痕迹都没有（根本没入库），排查时完全没有线索。
     *
     * 「是不是验证码短信」由上面那份关键词表把关已经够了。真要降噪，也该基于
     * 「有没有解析出验证码」这种更精确的信号，而不是「正文里出现过某个词」——
     * 后者在任何验证码短信里都可能命中（对账单、交易提醒都会带上那句话）。
     */
    fun shouldCollect(sender: String, body: String): Boolean {
        val text = "$sender $body".lowercase()
        return includeKeywords.any { text.contains(it.lowercase()) }
    }
}
