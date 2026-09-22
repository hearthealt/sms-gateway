package com.smsgateway.app.ui.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 收信/发生时刻。当天只给时刻，更早的带上日期。
 *
 * 队列与日志里绝大多数条目都是刚发生的，「07:42」比「09-20 07:42」少一半字，
 * 而信息量一样；跨天的那几条才需要日期来区分。
 *
 * 提到这里共用（原先是 QueueRow 的私有函数）：日志页要的是同一套写法，
 * 而两处各写一份的话，改一处分不清哪边是新的。
 */
fun formatClockTime(at: Long): String {
    if (at <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "MM-dd HH:mm", Locale.getDefault())
        .format(Date(at))
}

/**
 * 相对时间：把「某个时刻」说成「刚刚 / 12 秒前 / 3 分钟前 / 2 小时前」。
 *
 * 抽出来共用是因为它有两处消费者：状态卡的「已连接 · 12 秒前」，和「最近收到」那几行的
 * 「3 分钟前」。两处各写一份的话，改一处分不清哪边是新的。
 *
 * 传 [now] 而不是内部取当前时间：调用方要给的是**会自己走**的那个 now
 * （见 [rememberNow]），否则界面不会有重新求值的机会 —— 断网之后会一直停在「3 分钟前」。
 *
 * 服务端时间字符串怎么变成时刻见 [com.smsgateway.app.util.ServerTime]。
 */
fun formatRelative(at: Long?, now: Long): String {
    if (at == null || at <= 0L) return "无"
    val deltaSeconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 5 -> "刚刚"
        deltaSeconds < 60 -> "$deltaSeconds 秒前"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} 分钟前"
        else -> "${deltaSeconds / 3600} 小时前"
    }
}
