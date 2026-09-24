package com.smsgateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号码归一化与打码。
 *
 * <p>打码这一半守的是「**离开服务端的数据**」：设备端的诊断包会经过分享面板进到
 * 聊天工具里，那里只需要知道「有没有号码、两张卡分不分得清」，不需要完整值。
 * 边界全试一遍是因为这里写错的后果是**静默泄漏** —— 打出来的字符串看起来
 * 有星号，而旁边还留着足够拼回原号的数字。
 */
class PhoneUtilTest {

    @Test
    @DisplayName("归一化：去掉国家码与非数字字符")
    void normalize() {
        assertThat(PhoneUtil.normalize("+86 138-0013-8000")).isEqualTo("13800138000");
        assertThat(PhoneUtil.normalize("8613800138000")).isEqualTo("13800138000");
        assertThat(PhoneUtil.normalize("13800138000")).isEqualTo("13800138000");
        assertThat(PhoneUtil.normalize("+1 415-555-1234")).isEqualTo("14155551234");
        assertThat(PhoneUtil.normalize(null)).isEmpty();
        assertThat(PhoneUtil.normalize("无号码")).isEmpty();
    }

    @Test
    @DisplayName("打码：11 位保留前 3 后 4")
    void maskFullNumber() {
        assertThat(PhoneUtil.mask("13800138000")).isEqualTo("138****8000");
        assertThat(PhoneUtil.mask("+86 138-0013-8000")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("打码：短号的中间段必须全部打掉，不能留出可拼回的数字")
    void maskShortNumberDoesNotLeakMiddle() {
        // 7~10 位走「前 3 后 2」
        assertThat(PhoneUtil.mask("1234567")).isEqualTo("123****67");
        assertThat(PhoneUtil.mask("1234567890")).isEqualTo("123****90");
        // 6 位以下前 3 后 2 会重叠（等于把整个号露出来），所以只留首位
        assertThat(PhoneUtil.mask("12345")).isEqualTo("1****");
        assertThat(PhoneUtil.mask("1234")).isEqualTo("1****");
        assertThat(PhoneUtil.mask("1")).isEqualTo("1****");
    }

    @Test
    @DisplayName("打码：空值与读不出来的号码返回 null，由调用方决定怎么显示")
    void maskBlank() {
        assertThat(PhoneUtil.mask(null)).isNull();
        assertThat(PhoneUtil.mask("")).isNull();
        assertThat(PhoneUtil.mask("   ")).isNull();
        assertThat(PhoneUtil.mask("未知")).isNull();
    }

    @Test
    @DisplayName("打码后的字符串里不含原号码的任何连续片段（除刻意保留的首尾）")
    void maskedValueDropsTheMiddle() {
        String masked = PhoneUtil.mask("13800138000");

        // 中间那一整段必须不在结果里 —— 这是打码的全部意义
        assertThat(masked).doesNotContain("0013");
        assertThat(masked).doesNotContain("13800138");
    }
}
