package com.smsgateway.app.ui.utils

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
