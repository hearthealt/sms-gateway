package com.smsgateway.app.parser

/**
 * 短信采集过滤。
 *
 * 规则写死在这里，不做成可配置项：网关的职责就是只捞验证码短信，
 * 把开关暴露出去只会多一层没人会去调的配置。
 */
object SmsFilter {

    /**
     * 中文关键词，按子串匹配。中文没有词边界问题，且这些词本身就够独特。
     *
     * 繁体那几条是给港台短信的：原先两边都只认简体，`您的驗證碼是 123456` 会连
     * filter 都过不去 —— 而验证码一旦被 filter 拦下，服务端根本看不到这条短信，
     * 后面再好的提取规则也救不回来。
     */
    private val cjkKeywords = listOf(
        "验证码", "校验码", "动态码", "安全码", "动态密码", "认证码",
        "驗證碼", "認證碼", "動態密碼"
    )

    /**
     * 英文关键词，**必须带字母边界**。
     *
     * 不能用 `contains`：那样 `postcode`、`encode`、`decode`、`pinned` 全都会命中，
     * 而英文短信里出现这些词的概率远高于出现验证码。
     *
     * 边界用的是「两侧都不是字母」而不是 `\b`：
     * `Your code123456` 这种关键词与数字直接相连的写法，用 `\b` 会因为 `e` 与 `1`
     * 都是词字符而匹配不上；而只挡字母则既能放行它，又能挡住 `codes` / `encode`。
     */
    private val asciiKeywords = listOf("code", "otp", "pin", "passcode")

    private val asciiPattern = Regex(
        asciiKeywords.joinToString("|") { "(?<![A-Za-z])$it(?![A-Za-z])" },
        RegexOption.IGNORE_CASE
    )

    /**
     * 关键词表的放宽（2026-09-23）。
     *
     * 原先只有 `验证码 / 校验码 / 动态码 / verification code` 四条。漏掉的都不是边角：
     *
     * ```
     * 您的安全码是483920          → 服务端 CodeExtractor 本来就认「安全码」，却根本收不到
     * Your Uber code: 1234       → 只认字面 "verification code"，裸 code 不认
     * Telegram code: 12345       → 同上
     * 您的驗證碼是 123456         → 繁体一律漏
     * ```
     *
     * 放宽之后上传量会增加（`postal code` 这类英文也会进来一些），这是刻意的取舍：
     * **漏掉一条用户正在等的验证码，比多传几条噪音贵得多**，而噪音由服务端的
     * 采集规则（`CollectRuleEngine`）过滤 —— 它本来就是干这个的，且可配置。
     */

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
        val text = "$sender $body"
        if (cjkKeywords.any { text.contains(it) }) return true
        return asciiPattern.containsMatchIn(text)
    }
}
