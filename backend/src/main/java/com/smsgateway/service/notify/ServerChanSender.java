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
 * Server酱 Turbo（推到个人微信）。
 *
 * <p>官方要点：
 * <ul>
 *   <li>URL 形如 {@code https://sctapi.ftqq.com/&lt;SendKey&gt;.send} ——
 *       <b>凭据在路径上</b>，所以日志脱敏必须认长路径段（见 {@link NotifyRedactor}）</li>
 *   <li>参数 {@code title}（≤ 32 字符，**不能含换行**）+ {@code desp}（正文）</li>
 *   <li>成功判定：{@code code == 0}</li>
 *   <li>限流 50 条/分钟，但**免费额度只有 5 条/天** —— 够测试，不够日常用</li>
 * </ul>
 *
 * <p>免费额度这条值得说明：转发一个工作日几十条验证码，5 条/天第二天就用完了，
 * 而超额之后对端会直接拒。真要用它当主力渠道，得先确认账号的额度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerChanSender implements ChannelSender {

    private static final String ALLOWED_HOST = "sctapi.ftqq.com";

    /** 官方限制 title ≤ 32 字符。 */
    private static final int MAX_TITLE_CHARS = 32;

    /** 标题固定用它 —— 它同时充当了「这条消息是什么」的标识。 */
    private static final String TITLE = "短信转发";

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.SERVERCHAN;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方 50/min。但免费额度只有 5 条/天，限流再松也没用 —— 取 20 只是别把自己
        // 的额度在几秒内烧光然后收到一串失败
        return 20;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String sendKey = config.require("sendKey");
        String url = "https://" + ALLOWED_HOST + "/" + sendKey + ".send";
        ChannelEndpoints.requireHost(url, "Server酱", ALLOWED_HOST);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", sanitizeTitle());
        // 正文放 desp，不截断 —— 官方对 desp 没有长度限制
        payload.put("desp", message.text());

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        return judge(response.body());
    }

    /**
     * 标题不能含换行。
     *
     * <p>官方明确要求。虽然这里标题是写死的常量、不可能含换行，但仍然过一道 ——
     * 将来若改成可配，漏了这条会得到一个语焉不详的参数错误。
     */
    private String sanitizeTitle() {
        String title = TITLE.replaceAll("[\\r\\n]+", " ").trim();
        return NotifyMessageFactory.truncateChars(title, MAX_TITLE_CHARS);
    }

    private SendResult judge(String body) {
        if (body == null || body.isBlank()) {
            return SendResult.failed(200, "响应体为空");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("code");
            if (code != null && code.asInt() == 0) {
                return SendResult.ok(200, body);
            }
            return SendResult.failed(200, body);
        } catch (Exception e) {
            log.warn("Server酱响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return SendResult.failed(200, body);
        }
    }
}
