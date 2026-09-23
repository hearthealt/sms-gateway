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
    @DisplayName("英文的两种词序都能提取，含最常见的 is/are 写法")
    void extractsEnglish() {
        assertThat(CodeExtractor.extract("verification code 483920")).isEqualTo("483920");
        assertThat(CodeExtractor.extract("483920 is your verification code")).isEqualTo("483920");
        // 关键词与数字之间隔了系动词。此前提取不到，是靠设备端那个裸 6 位兜底
        // 意外救回来的；现在客户端不再认码，这里必须自己认得。
        assertThat(CodeExtractor.extract("Your verification code is 483920")).isEqualTo("483920");
    }

    @Test
    @DisplayName("字母数字混排的码也能提取 —— 码不一定是纯数字")
    void extractsAlphanumericCodes() {
        assertThat(CodeExtractor.extract("您的验证码是 A3F9K2，请勿泄露")).isEqualTo("A3F9K2");
        assertThat(CodeExtractor.extract("A3F9K2 是您的验证码")).isEqualTo("A3F9K2");
        assertThat(CodeExtractor.extract("Your verification code is 9K2M7P")).isEqualTo("9K2M7P");
        assertThat(CodeExtractor.extract("code: K7M2")).isEqualTo("K7M2");
        // 全数字仍然照常
        assertThat(CodeExtractor.extract("您的验证码是 483920")).isEqualTo("483920");
    }

    @Test
    @DisplayName("放宽到字母之后，边界守卫仍然是关键 —— 不从更长的字母数字串里截一段")
    void alphanumericStillRespectsBoundaries() {
        // 订单号/单号常见是 8 位以上的字母数字串，截一段出去就是错码
        assertThat(CodeExtractor.extract("验证码是 Order1234")).isEmpty();
        assertThat(CodeExtractor.extract("验证码是 AB12345678")).isEmpty();
        assertThat(CodeExtractor.extract("验证码是1234567890")).isEmpty();
    }

    @Test
    @DisplayName("纯字母的英文单词不是验证码 —— 光有边界挡不住，必须要求含数字")
    void englishWordsAreNotCodes() {
        // 这几条是字符类从 \d 放宽到 [A-Za-z0-9] 之后**真实出现过**的误判：
        // 它们都是 4~8 个字母、都被空格围着，边界守卫对它们无效。
        // 错值会被写进 sms:code:{phone} 推给等待方，比返回空难查得多。
        assertThat(CodeExtractor.extract("Your verification code is required")).isEmpty();
        assertThat(CodeExtractor.extract("【微信】您的WeChat验证码已发送，请查收")).isEmpty();

        // 关键词被更长的单词带进来同样不行 —— 前导边界负责这一段
        assertThat(CodeExtractor.extract("Your postcode is 200000")).isEmpty();
        assertThat(CodeExtractor.extract("This message is encoded")).isEmpty();

        // 反过来，关键词与数字之间隔了个英文单词时不能把那个词当码
        // （这里同时是**既有盲区**：`sent` 挡路之后 483920 也取不到，改动前后一致）
        assertThat(CodeExtractor.extract("Your code sent: 483920")).isEmpty();
    }

    @Test
    @DisplayName("设备端 filter 放行了的关键词，提取端必须也认 —— 否则只是多传了噪音")
    void keywordsTheClientFilterAdvancesAreExtractedHere() {
        assertThat(CodeExtractor.extract("您的驗證碼是 123456")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("您的認證碼是 123456")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("您的動態密碼是 123456")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("您的安全码是 483920")).isEqualTo("483920");
        assertThat(CodeExtractor.extract("Your OTP is 483920")).isEqualTo("483920");
        assertThat(CodeExtractor.extract("otp 123456")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("Your PIN is 1234")).isEqualTo("1234");
    }

    @Test
    @DisplayName("关键词与码直接相连也要认（code123456 / 验证码54869）")
    void keywordButtingAgainstTheCode() {
        // 英文侧此前认不出：码的前导守卫要求「前面不是字母数字」，而 code 的末尾是 `e`。
        // 中文侧一直是好的，「码」不是 ASCII 字母数字。
        assertThat(CodeExtractor.extract("code123456")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("Your code123456 is ready")).isEqualTo("123456");
        assertThat(CodeExtractor.extract("验证码54869")).isEqualTo("54869");
    }

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
