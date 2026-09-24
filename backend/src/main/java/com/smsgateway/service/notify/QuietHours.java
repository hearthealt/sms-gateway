package com.smsgateway.service.notify;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 静默时段：半夜别把人叫醒。
 *
 * <p>实现成**推迟投递**而不是丢弃：落在时段内的告警把 {@code next_retry_at} 设成时段
 * 结束时刻，重用了 {@code NotifyDispatcher.postpone} 已经建立的语义（限流取不到令牌时
 * 也写这里，往后推）。于是控制台的投递记录页会诚实地显示「待投递 + 下次重试 08:00」，
 * 而不是让那条告警凭空消失。一条「设备离线」早晨八点发出来仍然有用 —— 设备还离线着。
 *
 * <p>纯静态函数，因此可以在 JVM 单测里钉住跨零点那一段（那段最容易写错，
 * 而错法是把 23:00 判成不在 22:00–08:00 之内，于是半夜照常叫人）。
 */
public final class QuietHours {

    private QuietHours() {
    }

    /**
     * 某个时刻是否落在静默时段内。
     *
     * <p><b>{@code start == end} 时一律返回 false</b>，即「不算静默」。这个取值本身是
     * 有歧义的（也可以读成「全天静默」），所以我们**不靠相等性当开关** ——
     * 另有一个显式的 {@code alert.quiet-hours-enabled}。显式开关优于隐式约定：
     * 前者在界面上是一个能看见的选项，后者是埋在代码里的一个约定，而配 `22:00/22:00`
     * 的人多半是没想清楚自己想要哪一种。
     *
     * <p>跨零点（{@code start > end}，例如 22:00 → 08:00）时判据是「晚于等于 start
     * **或** 早于 end」。
     */
    public static boolean contains(LocalTime now, LocalTime start, LocalTime end) {
        if (now == null || start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * 这个时刻产生的告警该在什么时候真正发出去。
     *
     * <p>不在静默时段内时原样返回（立即发）。
     *
     * <p>找到的「结束时刻」必须真的**晚于** now：跨零点的窗口里，凌晨 2 点那个时刻的
     * 结束时刻是**今天**的 08:00，而晚上 23 点的结束时刻是**明天**的 08:00。
     * 少了那次「不晚就加一天」的判断，凌晨的那条会得到一个已经过去的时刻 ——
     * 于是它立刻发了出去，静默时段在半夜失效。
     */
    public static LocalDateTime nextRelease(LocalDateTime now, LocalTime start, LocalTime end) {
        if (now == null || !contains(now.toLocalTime(), start, end)) {
            return now;
        }
        LocalDateTime candidate = now.toLocalDate().atTime(end);
        return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }
}
