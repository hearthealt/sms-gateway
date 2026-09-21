package com.smsgateway.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 服务端下发的时间字符串 → 本机日期。
 *
 * 服务端给的是**不带时区**的本地时间（`2026-09-21T12:31:58`，或趋势接口的纯日期
 * `2026-09-21`）。这里按本机时区解释它 —— 设备与服务器在同一个局域网、同一个时区，
 * 这是成立的；跨时区部署才会错，而那种部署本来也没有出现过。
 *
 * 为什么不用 `OffsetDateTime.parse`：那个字段没有偏移量，会直接抛。
 *
 * 目前只有一个消费者：主页趋势图的「近 7 天」要把 `day` 转成「9/21」和「今天」。
 * 之前还有两个（把时刻转成「3 分钟前」、按日期筛今天的验证码），随「最近收到」那张卡
 * 和「复制今日验证码」一起删掉了 —— 留着不走的路只会让下一个人以为它有人用。
 */
object ServerTime {

    fun parseDay(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
}
