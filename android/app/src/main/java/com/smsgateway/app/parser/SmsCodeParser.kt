package com.smsgateway.app.parser

object SmsCodeParser {

    private val patterns = listOf(
        Regex("""验证码[是为：:\s]*(\d{4,8})"""),
        Regex("""校验码[是为：:\s]*(\d{4,8})"""),
        Regex("""动态码[是为：:\s]*(\d{4,8})"""),
        Regex("""code[：:\s]*(\d{4,8})""", RegexOption.IGNORE_CASE),
        Regex("""verification code[：:\s]*(\d{4,8})""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{6})\b""")
    )

    fun parse(text: String): String? {
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        }
    }
}