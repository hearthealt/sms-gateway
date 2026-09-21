package com.smsgateway.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 服务端下发的 `receiveTime` 字符串 → 本机时间。
 *
 * 服务端给的是 `2026-09-21T12:31:58`（`LocalDateTime`，**不带时区**，就是服务器所在时区的
 * 本地时间）。这里按本机时区解释它 —— 设备与服务器在同一个局域网、同一个时区，
 * 这是成立的；跨时区部署才会错，而那种部署本来也没有出现过。
 *
 * 放在 `util/` 而不是界面层，是因为两个地方要它：ViewModel 按「今天」筛验证码，
 * 界面把时刻显示成「3 分钟前」。
 *
 * 为什么不用 `OffsetDateTime.parse`：那个字段没有偏移量，会直接抛。
 */
object ServerTime {

    fun toEpochMillis(raw: String?): Long? = parse(raw)
        ?.atZone(ZoneId.systemDefault())
        ?.toInstant()
        ?.toEpochMilli()

    fun toLocalDate(raw: String?): LocalDate? = parse(raw)?.toLocalDate()

    /**
     * 只有日期没有时间的字符串（趋势接口的 `day`，形如 `2026-09-21`）→ LocalDate。
     *
     * 与 [toLocalDate] 分开：那个要求带时间部分（`2026-09-21T12:31:58`），
     * 拿纯日期去 `LocalDateTime.parse` 会抛。
     */
    fun parseDay(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    private fun parse(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }.getOrNull()
    }
}
