package com.smsgateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证码提取。
 *
 * <p>它是「这条短信到底出没出码」的唯一判据：漏了会让验证码取不到，
 * 宽松了会把订单号当成码返回给调用方 —— 后者更糟，返回错码比返回空更难排查。
 */
class CodeExtractorTest {

    @Test
    @DisplayName("关键词在前与在后都能提取")
    void extractsBothOrders() {
        assertThat(CodeExtractor.extract("您的验证码是483920，5分钟内有效")).isEqualTo("483920");
        assertThat(CodeExtractor.extract("483920 是您的验证码")).isEqualTo("483920");
    }

    @Test
    @DisplayName("英文的两种词序都能提取")
    void extractsEnglish() {
        assertThat(CodeExtractor.extract("verification code 483920")).isEqualTo("483920");
        assertThat(CodeExtractor.extract("483920 is your verification code")).isEqualTo("483920");
    }

    // 注意一个**已知的提取盲区**（v1 就有，本次未改动）：
    //   "Your verification code is 483920"
    // 提取不到 —— 关键词与数字之间隔了一个 "is"，而模式里只允许空白与「是/为/：」。
    // 这是英文短信最常见的写法，中文场景不受影响（「您的验证码是 483920」能匹配）。
    // 改它会动到 v1 的提取行为，不在本次范围内。

    @Test
    @DisplayName("裸数字不当验证码 —— 订单号比验证码常见得多，返回错码比返回空更糟")
    void plainNumberIsNotACode() {
        assertThat(CodeExtractor.extract("您的订单号 123456 已发货")).isEmpty();
        assertThat(CodeExtractor.extract("余额 1234567890 元")).isEmpty();
    }

    @Test
    @DisplayName("不从更长的数字串里截一段")
    void doesNotSliceLongerDigits() {
        // 1234567890 是 10 位，不该被切成 4~8 位的「验证码」
        assertThat(CodeExtractor.extract("验证码是1234567890")).isEmpty();
    }

    @Test
    @DisplayName("提取不到时返回空串而不是 null —— 调用方普遍直接判空，null 会在各处引 NPE")
    void returnsEmptyStringNotNull() {
        assertThat(CodeExtractor.extract(null)).isEmpty();
        assertThat(CodeExtractor.extract("")).isEmpty();
        assertThat(CodeExtractor.extract("今天天气不错")).isEmpty();
    }
}
