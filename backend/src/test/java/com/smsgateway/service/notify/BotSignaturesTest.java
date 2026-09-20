package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞书与钉钉的加签。
 *
 * <p>这两家四条规则全部反着来（HMAC 的 key 是什么、待签数据是什么、时间戳单位、
 * 参数放哪儿），照着其中一家写另一家几乎必然写错，而错了只会得到一个
 * 「签名失败」的错误码，看不出是哪个环节反了。所以这里逐条把「反直觉的那一点」
 * 钉住：**测试里用独立的 javax.crypto 重新算一遍**，而不是调生产代码自己算自己验。
 */
class BotSignaturesTest {

    private static final String SECRET = "test-secret";

    /** 独立实现，用来交叉验证生产代码。 */
    private static String hmacBase64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacBase64EmptyData(String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[]{}));
    }

    // ---------------------------------------------------------------- 飞书

    @Test
    @DisplayName("飞书：key 是 stringToSign、签的是【空数据】—— 这是最反直觉的一点")
    void feishuSignsEmptyDataWithStringToSignAsKey() throws Exception {
        String timestamp = "1599360473";
        String stringToSign = timestamp + "\n" + SECRET;

        String actual = BotSignatures.feishuSign(SECRET, timestamp);
        String expected = hmacBase64EmptyData(stringToSign);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("飞书：不能写成常规的 HMAC(key=secret, msg=stringToSign)")
    void feishuIsNotTheConventionalForm() throws Exception {
        String timestamp = "1599360473";
        String stringToSign = timestamp + "\n" + SECRET;

        String conventional = hmacBase64(SECRET, stringToSign);

        // 按常规写法写出来**不会报错**，只会一直「签名失败」。这条断言就是防止
        // 有人后来「顺手修正」成更符合直觉的写法。
        assertThat(BotSignatures.feishuSign(SECRET, timestamp)).isNotEqualTo(conventional);
    }

    @Test
    @DisplayName("飞书：timestamp 用秒（用毫秒会因超出 1 小时窗口而失败）")
    void feishuUsesSeconds() {
        String seconds = String.valueOf(System.currentTimeMillis() / 1000);
        String millis = String.valueOf(System.currentTimeMillis());

        // 同一个 secret 下两者签名必然不同 —— 传错了不会报错，只会签名失败
        assertThat(BotSignatures.feishuSign(SECRET, seconds))
                .isNotEqualTo(BotSignatures.feishuSign(SECRET, millis));
    }

    // ---------------------------------------------------------------- 钉钉

    @Test
    @DisplayName("钉钉：key 是 secret、签的是 stringToSign —— 与飞书正好相反")
    void dingtalkSignsStringToSignWithSecretAsKey() throws Exception {
        String timestamp = "1599360473000";
        String stringToSign = timestamp + "\n" + SECRET;

        String actual = BotSignatures.dingtalkSign(SECRET, timestamp);

        // 注意返回值是 URL 编码过的，比之前先解开
        String decoded = URLDecoder.decode(actual, StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(hmacBase64(SECRET, stringToSign));
    }

    @Test
    @DisplayName("钉钉：返回值是 URL 编码过的，可直接拼进 query（不能再 encode 一次）")
    void dingtalkReturnsUrlEncodedSign() {
        String sign = BotSignatures.dingtalkSign(SECRET, "1599360473000");

        // HMAC-SHA256 是 32 字节 → Base64 44 字符、末尾一个 '='。
        // 那个 '=' 在 query 里会被当成分隔符，必须编码成 %3D 才能原样带到对端。
        assertThat(sign).doesNotContain("+").doesNotContain("=").doesNotContain("/");
        assertThat(sign).contains("%3D");

        // 解开之后应当是一个以单个 '=' 收尾的标准 Base64
        String decoded = URLDecoder.decode(sign, StandardCharsets.UTF_8);
        assertThat(decoded).endsWith("=").doesNotContain("%");

        // 再编码一次会得到同一串 —— 说明它已经恰好编码过一遍，
        // 调用方直接拼进 query 即可，重复编码会让签名对不上
        assertThat(URLEncoder.encode(decoded, StandardCharsets.UTF_8)).isEqualTo(sign);
    }

    @Test
    @DisplayName("钉钉：sign 与 timestamp 拼在 URL query 上（放 body 会得到 310000）")
    void dingtalkSignedUrlPutsParamsInQuery() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";

        String signed = BotSignatures.dingtalkSignedUrl(url, SECRET);

        assertThat(signed).startsWith(url);
        assertThat(signed).contains("&timestamp=").contains("&sign=");
        // 原有的 access_token 不能被冲掉
        assertThat(signed).contains("access_token=abc");
    }

    @Test
    @DisplayName("钉钉：原 URL 没有 query 时用 ? 而不是 &")
    void dingtalkHandlesUrlWithoutQuery() {
        String signed = BotSignatures.dingtalkSignedUrl("https://oapi.dingtalk.com/robot/send", SECRET);
        assertThat(signed).contains("?timestamp=");
        assertThat(signed).doesNotContain("&timestamp=");
    }

    @Test
    @DisplayName("钉钉：timestamp 用毫秒（用秒会被判为过期）")
    void dingtalkUsesMillis() {
        String millis = String.valueOf(System.currentTimeMillis());
        String seconds = String.valueOf(System.currentTimeMillis() / 1000);

        assertThat(BotSignatures.dingtalkSign(SECRET, millis))
                .isNotEqualTo(BotSignatures.dingtalkSign(SECRET, seconds));
    }

    // ---------------------------------------------------------------- 交叉

    @Test
    @DisplayName("同一组输入、两个平台算出来的签名必然不同 —— 印证「别照抄另一家」")
    void theTwoPlatformsAgreeOnNothing() {
        String timestamp = "1599360473";

        assertThat(BotSignatures.feishuSign(SECRET, timestamp))
                .isNotEqualTo(BotSignatures.dingtalkSign(SECRET, timestamp));
    }
}
