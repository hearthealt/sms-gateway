package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用 webhook 的出站签名。
 *
 * <p>为什么不让管理员自己写签名逻辑（配模板）：签名一旦算错，对端只回一个 401，
 * 现场完全看不出是签名的问题还是密钥的问题。用 Standard Webhooks 那套规范，
 * 对端有现成库能对上。
 */
class WebhookSignerTest {

    private static final String SECRET = Base64.getEncoder().encodeToString("test-secret".getBytes());

    @Test
    @DisplayName("三个头都带上，签名有 v1 前缀")
    void producesStandardHeaders() {
        Map<String, String> headers = WebhookSigner.sign(SECRET, "{\"a\":1}");

        assertThat(headers).containsKeys(
                WebhookSigner.HEADER_ID, WebhookSigner.HEADER_TIMESTAMP, WebhookSigner.HEADER_SIGNATURE);
        assertThat(headers.get(WebhookSigner.HEADER_SIGNATURE)).startsWith("v1,");
        // 时间戳按规范用**秒**
        assertThat(Long.parseLong(headers.get(WebhookSigner.HEADER_TIMESTAMP)))
                .isBetween(1_600_000_000L, 4_000_000_000L);
    }

    @Test
    @DisplayName("同一组输入签名确定，且能被 verify 验过")
    void signatureIsVerifiable() {
        String signature = WebhookSigner.computeSignature(SECRET, "msg_1", "1700000000", "{\"a\":1}");

        assertThat(WebhookSigner.verify(SECRET, "msg_1", "1700000000", "{\"a\":1}", signature)).isTrue();
    }

    @Test
    @DisplayName("payload 被改过就验不过")
    void detectsPayloadTampering() {
        String signature = WebhookSigner.computeSignature(SECRET, "msg_1", "1700000000", "{\"a\":1}");

        assertThat(WebhookSigner.verify(SECRET, "msg_1", "1700000000", "{\"a\":2}", signature)).isFalse();
    }

    @Test
    @DisplayName("换了密钥就验不过")
    void detectsWrongSecret() {
        String signature = WebhookSigner.computeSignature(SECRET, "msg_1", "1700000000", "{}");
        String other = Base64.getEncoder().encodeToString("other-secret".getBytes());

        assertThat(WebhookSigner.verify(other, "msg_1", "1700000000", "{}", signature)).isFalse();
    }

    @Test
    @DisplayName("时间戳或 msg_id 变了也验不过 —— 三段都是一起签的")
    void bindsAllThreeParts() {
        String signature = WebhookSigner.computeSignature(SECRET, "msg_1", "1700000000", "{}");

        assertThat(WebhookSigner.verify(SECRET, "msg_2", "1700000000", "{}", signature)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "msg_1", "1700000001", "{}", signature)).isFalse();
    }

    @Test
    @DisplayName("没配密钥时不加签名头 —— 签名是可选的")
    void noHeadersWithoutSecret() {
        assertThat(WebhookSigner.sign(null, "{}")).isEmpty();
        assertThat(WebhookSigner.sign("", "{}")).isEmpty();
    }

    @Test
    @DisplayName("密钥不是合法 Base64 时按原始字节用，而不是直接报错")
    void toleratesNonBase64Secret() {
        // 管理员贴进来的可能是任意字符串，为此拒绝签发不划算
        String signature = WebhookSigner.computeSignature("plain-text-secret", "msg_1", "1700000000", "{}");

        assertThat(signature).isNotBlank();
        assertThat(WebhookSigner.verify("plain-text-secret", "msg_1", "1700000000", "{}", signature)).isTrue();
    }
}
