package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静默时段的边界。
 *
 * <p>这一段最容易写错的地方是**跨零点**：把 22:00–08:00 写成「start 到 end 之间」，
 * 判据在半夜就是恒假的 —— 于是静默时段在真正需要它的那几个小时里完全失效，
 * 而白天测试时一切正常。所以下面每一条跨零点的边界都单独钉一遍。
 */
class QuietHoursTest {

    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(8, 0);

    @Test
    @DisplayName("跨零点的窗口：22:00–08:00")
    void overnightWindow() {
        assertThat(QuietHours.contains(LocalTime.of(22, 0), NIGHT_START, NIGHT_END)).isTrue();
        assertThat(QuietHours.contains(LocalTime.of(23, 30), NIGHT_START, NIGHT_END)).isTrue();
        // 零点之后那一段 —— 少了跨零点判断的话，正是这里会失效
        assertThat(QuietHours.contains(LocalTime.of(0, 30), NIGHT_START, NIGHT_END)).isTrue();
        assertThat(QuietHours.contains(LocalTime.of(7, 59), NIGHT_START, NIGHT_END)).isTrue();
        // 结束时刻是**开区间**：8:00 已经可以发了
        assertThat(QuietHours.contains(LocalTime.of(8, 0), NIGHT_START, NIGHT_END)).isFalse();
        assertThat(QuietHours.contains(LocalTime.of(12, 0), NIGHT_START, NIGHT_END)).isFalse();
        assertThat(QuietHours.contains(LocalTime.of(21, 59), NIGHT_START, NIGHT_END)).isFalse();
    }

    @Test
    @DisplayName("同日窗口：09:00–17:00")
    void sameDayWindow() {
        assertThat(QuietHours.contains(LocalTime.of(9, 0), LocalTime.of(9, 0), LocalTime.of(17, 0))).isTrue();
        assertThat(QuietHours.contains(LocalTime.of(13, 0), LocalTime.of(9, 0), LocalTime.of(17, 0))).isTrue();
        assertThat(QuietHours.contains(LocalTime.of(16, 59), LocalTime.of(9, 0), LocalTime.of(17, 0))).isTrue();
        assertThat(QuietHours.contains(LocalTime.of(17, 0), LocalTime.of(9, 0), LocalTime.of(17, 0))).isFalse();
        assertThat(QuietHours.contains(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(17, 0))).isFalse();
    }

    @Test
    @DisplayName("start == end 时不算静默 —— 那个取值本身有歧义，由开关决定，不靠相等性推断")
    void equalStartAndEndIsNeverQuiet() {
        LocalTime same = LocalTime.of(22, 0);
        assertThat(QuietHours.contains(LocalTime.of(22, 0), same, same)).isFalse();
        assertThat(QuietHours.contains(LocalTime.of(3, 0), same, same)).isFalse();
    }

    @Test
    @DisplayName("不在时段内时「什么时候发」就是现在")
    void nextReleaseOutsideWindowIsNow() {
        LocalDateTime noon = LocalDateTime.of(2026, 9, 24, 12, 0);
        assertThat(QuietHours.nextRelease(noon, NIGHT_START, NIGHT_END)).isEqualTo(noon);
    }

    @Test
    @DisplayName("跨零点：晚上 23 点产生的告警等到**明天** 08:00")
    void nextReleaseAfterMidnightCrossing() {
        LocalDateTime night = LocalDateTime.of(2026, 9, 24, 23, 0);

        assertThat(QuietHours.nextRelease(night, NIGHT_START, NIGHT_END))
                .isEqualTo(LocalDateTime.of(2026, 9, 25, 8, 0));
    }

    @Test
    @DisplayName("跨零点：凌晨 2 点产生的告警等到**今天** 08:00")
    void nextReleaseBeforeDawnEndsToday() {
        // 这一条是上面那条的另一半，也是「不加一天判断」时会写错的那一处：
        // 凌晨 2 点的结束时刻是今天的 8:00，而不是明天 —— 写错的话那条告警会
        // 得到一个「过去」的时刻，于是立刻发出去，静默时段在半夜失效。
        LocalDateTime dawn = LocalDateTime.of(2026, 9, 24, 2, 0);

        assertThat(QuietHours.nextRelease(dawn, NIGHT_START, NIGHT_END))
                .isEqualTo(LocalDateTime.of(2026, 9, 24, 8, 0));
    }

    @Test
    @DisplayName("同日窗口：12 点产生的告警等到今天 17:00")
    void nextReleaseSameDayWindow() {
        LocalDateTime noon = LocalDateTime.of(2026, 9, 24, 12, 0);

        assertThat(QuietHours.nextRelease(noon, LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 24, 17, 0));
    }

    @Test
    @DisplayName("返回的时刻本身不在时段内 —— 否则它会立刻被再次推迟，永远发不出去")
    void nextReleaseIsNotInsideWindow() {
        LocalDateTime night = LocalDateTime.of(2026, 9, 24, 23, 0);
        LocalDateTime release = QuietHours.nextRelease(night, NIGHT_START, NIGHT_END);

        assertThat(QuietHours.contains(release.toLocalTime(), NIGHT_START, NIGHT_END)).isFalse();
    }
}
