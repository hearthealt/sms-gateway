package com.smsgateway.service.notify;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞书与钉钉自定义机器人的加签。
 *
 * <p><b>两家放进同一个类，是因为它们四处差异全部反着来</b> —— 分开写的话，
 * 照着其中一家写另一家几乎必然写错，而错了只会得到一个「签名失败」的错误码，
 * 看不出是哪个环节反了。放一起，差异一眼可见：
 *
 * <table border="1">
 *   <caption>飞书 vs 钉钉</caption>
 *   <tr><th></th><th>钉钉</th><th>飞书</th></tr>
 *   <tr><td>HMAC 的 key</td><td>{@code secret}</td><td>{@code timestamp + "\n" + secret}</td></tr>
 *   <tr><td>待签数据</td><td>{@code timestamp + "\n" + secret}</td><td><b>空字节</b></td></tr>
 *   <tr><td>timestamp 单位</td><td><b>毫秒</b></td><td><b>秒</b></td></tr>
 *   <tr><td>参数位置</td><td><b>URL query</b></td><td><b>JSON body</b></td></tr>
 * </table>
 *
 * <p>飞书那条尤其反直觉：不是常规的 {@code HMAC(key=secret, msg=stringToSign)}，
 * 而是**把 stringToSign 当作 HMAC 的 key、去签一段空数据**。按常规写法写出来
 * 不会报错，只会一直签名失败。
 */
public class BotSignatures {

    private BotSignatures() {
    }

    // ---------------------------------------------------------------- 飞书

    /**
     * 飞书加签。
     *
     * <pre>
     * stringToSign = timestamp + "\n" + secret        // timestamp 单位【秒】
     * sign = Base64(HMAC-SHA256(key = stringToSign, data = 空字节))
     * </pre>
     *
     * @param timestampSeconds 秒级时间戳，距当前不得超过 1 小时
     * @return Base64 签名
     */
    public static String feishuSign(String secret, String timestampSeconds) {
        try {
            String stringToSign = timestampSeconds + "\n" + secret;

            Mac mac = Mac.getInstance("HmacSHA256");
            // **反直觉的地方在这里**：key 是 stringToSign，签的是空数据。
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sign = mac.doFinal(new byte[]{});

            return Base64.getEncoder().encodeToString(sign);
        } catch (Exception e) {
            throw new IllegalStateException("飞书加签失败", e);
        }
    }

    // ---------------------------------------------------------------- 钉钉

    /**
     * 钉钉加签。
     *
     * <pre>
     * timestamp = 毫秒时间戳
     * stringToSign = timestamp + "\n" + secret
     * sign = urlEncode(Base64(HMAC-SHA256(key = secret, data = stringToSign)))
     * </pre>
     *
     * <p>{@code sign} 与 {@code timestamp} **必须拼到请求 URL 的 query 上**，
     * 放 body 会得到 {@code 310000}。官方的原话就是这么说的 —— 这条最容易踩，
     * 因为飞书恰好是要放 body 的。
     *
     * @param timestampMillis 毫秒级时间戳
     * @return 已 URL 编码的签名（可直接拼进 query，不要再 encode 一次）
     */
    public static String dingtalkSign(String secret, String timestampMillis) {
        try {
            String stringToSign = timestampMillis + "\n" + secret;

            Mac mac = Mac.getInstance("HmacSHA256");
            // 与飞书相反：key 是 secret，签的是 stringToSign
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));

            String base64 = Base64.getEncoder().encodeToString(hmac);
            return URLEncoder.encode(base64, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败", e);
        }
    }

    /** 钉钉：把 {@code timestamp} 与 {@code sign} 拼到已有 query 的 URL 上。 */
    public static String dingtalkSignedUrl(String webhookUrl, String secret) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = dingtalkSign(secret, timestamp);
        String separator = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
    }
}
