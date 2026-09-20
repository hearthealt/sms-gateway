package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 飞书自定义机器人。
 *
 * <p>官方要点：
 * <ul>
 *   <li>URL 形如 {@code https://open.feishu.cn/open-apis/bot/v2/hook/<HOOK_ID>}</li>
 *   <li>限流 <b>100 次/分钟、5 次/秒</b>；官方建议避开 10:00、17:30 等整点/半点</li>
 *   <li>请求体 ≤ 20 KB</li>
 *   <li>加签的 {@code timestamp}（**秒**）与 {@code sign} 放 <b>JSON body</b> ——
 *       钉钉恰好相反，见 {@link BotSignatures}</li>
 *   <li>{@code timestamp} 距当前不得超过 1 小时</li>
 *   <li>错误码：{@code 19021} 签名失败 / {@code 19022} IP 不在白名单 /
 *       {@code 19024} 关键词未命中 / {@code 9499} Bad Request</li>
 * </ul>
 *
 * <p>另一个硬限制：**同一个机器人无法添加到多个群**，多群转发得为每个群单独建机器人。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuBotSender implements ChannelSender {

    private static final String ALLOWED_HOST = "open.feishu.cn";

    /** 官方限制请求体 ≤ 20KB，留出 JSON 外壳的余量。 */
    private static final int MAX_CONTENT_BYTES = 16 * 1024;

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.FEISHU_BOT;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方硬限 100/min，取 60 留余量：还有 5 次/秒那条瞬时限制，
        // 而官方明确建议避开整点/半点的高峰
        return 60;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String url = config.require("webhookUrl");
        ChannelEndpoints.requireHost(url, "飞书机器人", ALLOWED_HOST);

        ObjectNode payload = objectMapper.createObjectNode();

        String secret = config.str("secret");
        if (secret != null && !secret.isBlank()) {
            // 秒级时间戳 —— 单位搞错的话签名必然不匹配，而错误码只说「签名失败」
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            payload.put("timestamp", timestamp);
            payload.put("sign", BotSignatures.feishuSign(secret, timestamp));
        }

        payload.put("msg_type", "text");
        payload.putObject("content")
                .put("text", NotifyMessageFactory.truncateUtf8(message.text(), MAX_CONTENT_BYTES));

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            return SendResult.failed(response.statusCode(), response.body());
        }

        Integer code = parseCode(response.body());
        if (code != null && code == 0) {
            return SendResult.ok(200, response.body());
        }

        // 非 0 或解不出来都算失败。原始响应带回去，由分类器按错误码/文案判是否重试
        return SendResult.failed(200, response.body());
    }

    /**
     * 飞书的成功码。
     *
     * <p>v2 hook 回的是 {@code {"code":0,...}}，但历史上也见过
     * {@code {"StatusCode":0,...}} 的老格式，两种都认 —— 只认一种的话，
     * 遇到另一种会把成功的投递记成失败并反复重试。
     */
    private Integer parseCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("code")) {
                return node.get("code").asInt();
            }
            if (node.has("StatusCode")) {
                return node.get("StatusCode").asInt();
            }
            return null;
        } catch (Exception e) {
            log.warn("飞书响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return null;
        }
    }
}
