package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Slack Incoming Webhook。
 *
 * <p>官方要点，其中一条与大量二手博客的说法相反：
 * <ul>
 *   <li>URL 形如 {@code https://hooks.slack.com/services/T.../B.../XXXX}</li>
 *   <li>🚨 <b>payload 里的 {@code channel} / {@code username} / {@code icon_emoji} /
 *       {@code icon_url} 全部无效</b>（官方原文明确说明）。这里就干脆不发这几个字段 ——
 *       发了也不会生效，只会让配置的人以为自己能改</li>
 *   <li>限流 <b>1 条/秒</b>，官方警告「超限后继续发送可能导致应用被<b>永久禁用</b>」</li>
 *   <li>顶层 {@code text} 上限 <b>3000 字符</b>（按字符）</li>
 *   <li>错误要分类：{@code invalid_payload} / {@code no_service} /
 *       {@code no_active_hooks} / {@code action_prohibited} **均不可重试** ——
 *       盲目重试可能触发永久禁用。这几条由 {@link NotifyErrorClassifier} 认出来</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackSender implements ChannelSender {

    private static final String ALLOWED_HOST = "hooks.slack.com";

    /** 顶层 text 上限 3000 字符。 */
    private static final int MAX_TEXT_CHARS = 2900;

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.SLACK_WEBHOOK;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方硬限 1 条/秒 = 60/min，但「超限即可能永久禁用」的代价太大，
        // 取 15 留足余量
        return 15;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String url = config.require("webhookUrl");
        ChannelEndpoints.requireHost(url, "Slack", ALLOWED_HOST);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", NotifyMessageFactory.truncateChars(message.text(), MAX_TEXT_CHARS));

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            // Slack 成功时回一个纯文本 "ok"，不是 JSON
            return SendResult.ok(response.statusCode(), response.body());
        }

        // 失败体形如 {"ok":false,"error":"no_active_hooks"} —— 分类器按 error 文案识别
        return SendResult.failed(response.statusCode(), response.body());
    }
}
