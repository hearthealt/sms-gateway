package com.smsgateway.service.notify;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通用 webhook 的出站签名，对齐 <a href="https://www.standardwebhooks.com/">Standard Webhooks</a>。
 *
 * <p>为什么不让管理员自己写签名逻辑（配模板）：签名一旦算错，对端只会回一个 401，
 * 而现场完全看不出是签名的问题还是密钥的问题。用一套业界通行的规范，
 * 对端要接的话有现成库、也能对上文档。
 *
 * <p>规范要点：签的是 {@code msg_id.timestamp.payload} 三段以点拼接，HMAC-SHA256、
 * Base64 编码，放进 {@code webhook-signature} 头，前缀 {@code v1,}。
 * 时间戳用**秒**，对端据此拒绝过老的请求（防重放）。
 */
public class WebhookSigner {

    public static final String HEADER_ID = "webhook-id";
    public static final String HEADER_TIMESTAMP = "webhook-timestamp";
    public static final String HEADER_SIGNATURE = "webhook-signature";

    private WebhookSigner() {
    }

    /**
     * @param secret 共享密钥，明文（加密存储是渠道配置那一层的事）
     * @return 要附加的请求头；{@code secret} 为空时返回空 Map（签名是可选的）
     */
    public static Map<String, String> sign(String secret, String payload) {
        if (secret == null || secret.isBlank()) {
            return Map.of();
        }

        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        String signature = computeSignature(secret, messageId, timestamp, payload);

        Map<String, String> headers = new HashMap<>();
        headers.put(HEADER_ID, messageId);
        headers.put(HEADER_TIMESTAMP, timestamp);
        headers.put(HEADER_SIGNATURE, "v1," + signature);
        return headers;
    }

    /** 单独提出来是为了能直接用官方测试向量做单测。 */
    static String computeSignature(String secret, String messageId, String timestamp, String payload) {
        try {
            String signedContent = messageId + "." + timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            // Standard Webhooks 的密钥是 base64 编码的；解不开就按原始字节用 ——
            // 管理员贴的可能是任意字符串，为此直接报错不划算。
            mac.init(new SecretKeySpec(decodeSecret(secret), "HmacSHA256"));
            byte[] digest = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("webhook 签名计算失败", e);
        }
    }

    private static byte[] decodeSecret(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            // 解出来是空的话（例如 secret 本身就是空串）仍然退回原文
            return decoded.length > 0 ? decoded : secret.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 给自检用：确认库里的 HMAC 实现与我们算的一致。 */
    public static boolean verify(String secret, String messageId, String timestamp, String payload, String signature) {
        byte[] expected = computeSignature(secret, messageId, timestamp, payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
