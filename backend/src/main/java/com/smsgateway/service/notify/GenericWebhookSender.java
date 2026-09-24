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
 *
 * <p><b>故障告警走同一个渠道，但载荷不同</b>：告警没有发送方、没有接收号码、
 * 没有关联短信，所以那几项一律**不出现**（而不是补 0 或空串），改成一个
 * {@code "alert": true}。见 {@link #defaultPayload}。
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
     *
     * <p>**包级可见是为了能被单测直接调**（同 {@code NotifyDispatcher.applyResult}）：
     * 这是一份对外的线上格式，而它有两副形态（短信 / 告警），走 {@code send()} 去测
     * 要连着 HTTP 客户端与 SSRF 校验一起桩掉，绕远且测不到重点。
     */
    String defaultPayload(RenderedMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("text", message.text());

        // 告警**没有**发送方 / 接收号码 / 关联短信，那几项一律不出现在载荷里，
        // 而不是补一个 0 或空串。
        //
        // 原先这里把 smsMessageId 缺省写成 0L，那是个会误导对端的具体数字（0 看起来
        // 就像一条真实的记录 id，对端拿它去查只会得到「查不到」）。空字符串同理：
        // 对端分不清「这条没有号码」与「有号码但读不出来」。
        // 这几条新出现的「没有」是告警带来的 —— 告警之前的每一条投递都有短信，
        // 所以把缺省值改掉不会破坏任何既有对端的解析。
        if (message.smsMessageId() != null) {
            node.put("smsMessageId", message.smsMessageId());
            node.put("sender", message.sender());
            node.put("phone", message.phone());
            node.put("device", message.deviceName());
            node.put("time", message.time());
        } else {
            node.put("alert", true);
            // device 对告警也可能有意义（设备离线那类），有值就带上
            if (message.deviceName() != null && !message.deviceName().isBlank()) {
                node.put("device", message.deviceName());
            }
        }
        return node.toString();
    }
}
