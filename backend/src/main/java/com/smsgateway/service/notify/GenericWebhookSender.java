package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通用 webhook：自定义 URL + 可选 HMAC 签名，用来对接公司内部系统或长尾渠道。
 *
 * <p>它是**唯一**允许管理员填任意地址的渠道，因此 {@link SsrfGuard} 那一关不能跳 ——
 * 管理后台账号一旦被盗，这个功能就是一个现成的内网探测器。
 * 对接内网服务是合理需求，所以留了 {@code allowPrivateNetwork} 开关，但默认关。
 *
 * <p>配置项：
 * <pre>
 * {
 *   "url": "https://internal.example.com/hook",
 *   "hmacSecret": "可选的共享密钥",
 *   "allowPrivateNetwork": false
 * }
 * </pre>
 *
 * <p>发出去的 JSON 结构固定：{@code text} 是短信原文（含一行元信息），
 * 另外带上 {@code sender} / {@code phone} / {@code device} / {@code time} /
 * {@code smsMessageId}。**不做可配的 payload 模板** —— 理由见
 * {@link NotifyMessageFactory}，这套东西的配置面比收益大得多。
 * 对端只需要读 {@code text} 那一个字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenericWebhookSender implements ChannelSender {

    private final NotifyHttpClient http;
    private final SsrfGuard ssrfGuard;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.GENERIC_WEBHOOK;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 对端未知，给一个保守但有意义的默认值：既不至于一条条慢慢发，
        // 也不会因为我们突发送几千条把人家打挂。管理员可以按对端能力调整。
        return 60;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String url = config.require("url");
        boolean allowPrivateNetwork = config.bool("allowPrivateNetwork", false);

        // 每次发送前都验，不是保存配置时验一次就完 —— DNS 记录会变，
        // 存的时候通过不代表发的时候还通过。
        ssrfGuard.validate(url, allowPrivateNetwork);

        String payload = defaultPayload(message);

        Map<String, String> headers = WebhookSigner.sign(config.str("hmacSecret"), payload);
        NotifyHttpResponse response = http.postJson(url, payload, headers);

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return SendResult.ok(response.statusCode(), response.body());
        }

        // 3xx 也算失败。协议上不应出现 —— 我们要求不跟随重定向，
        // 而对端若真的回了 3xx，说明它想让我们去另一个地方，那个地方没经过 SSRF 校验。
        return SendResult.failed(response.statusCode(), response.body());
    }

    /**
     * 固定结构的 JSON。
     *
     * <p>用 Jackson 建节点而不是拼字符串：短信正文里出现引号、反斜杠、换行都很正常，
     * 手工拼会造出非法 JSON，而对端只会回一个语焉不详的 400，看不出是我们拼坏的。
     */
    private String defaultPayload(RenderedMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("text", message.text());
        node.put("sender", message.sender());
        node.put("phone", message.phone());
        node.put("device", message.deviceName());
        node.put("time", message.time());
        node.put("smsMessageId", message.smsMessageId() == null ? 0L : message.smsMessageId());
        return node.toString();
    }
}
