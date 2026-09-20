package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * WxPusher —— 把消息推到**个人微信**的可行路径之一。
 *
 * <p>官方要点：
 * <ul>
 *   <li>标准模式：{@code POST https://wxpusher.zjiecode.com/api/send/message}，
 *       请求体带 {@code appToken} + {@code uids}（或 {@code topicIds}）</li>
 *   <li>SPT 极简模式：{@code GET /api/send/message/{SPT}/{内容}}，**无需注册应用**</li>
 *   <li>成功判定：{@code code == 1000}（<b>注意不是 200</b>）</li>
 *   <li>content ≤ 40000 字符且 UTF-8 ≤ 65535 字节；单请求 uids ≤ 2000、topicIds ≤ 5</li>
 *   <li>限流约 2 QPS；官方明确「WxPusher 是免费的推送服务」，**无每日条数上限**</li>
 * </ul>
 *
 * <p>为什么两种模式都做：SPT 不用注册应用，对「就想推到自己微信」的人门槛最低；
 * 但它把内容放在 **URL 路径**里，长短信会撞上 URL 长度上限。
 * 配了 SPT 就用 SPT，否则走标准模式 —— 后者没有长度问题，但要去官网注册一个应用。
 *
 * <p>安全提示：SPT 泄漏后任何人都能给你发消息，按凭据处理（渠道配置是加密存储的）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxPusherSender implements ChannelSender {

    private static final String ALLOWED_HOST = "wxpusher.zjiecode.com";
    private static final String SEND_PATH = "/api/send/message";

    /** 标准模式：content ≤ 40000 字符，且 UTF-8 ≤ 65535 字节。取两者的保守交集。 */
    private static final int MAX_CONTENT_CHARS = 20000;

    /**
     * SPT 模式的内容上限，远小于标准模式。
     *
     * <p>因为内容整个塞在 URL 路径里：浏览器与网关普遍在 2KB~8KB 处截断 URL，
     * 而中文 URL 编码后一个字要 9 个字符（{@code %E9%AA%8C}）。取 300 是很保守的值，
     * 超了就会被对端以「参数错误」拒掉，而错误信息不会告诉你是 URL 太长了。
     */
    private static final int MAX_SPT_CONTENT_CHARS = 300;

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.WXPUSHER;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方约 2 QPS = 120/min，取一半留余量
        return 60;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String spt = config.str("spt");
        return (spt != null && !spt.isBlank())
                ? sendViaSpt(spt, message)
                : sendViaApp(config, message);
    }

    private SendResult sendViaSpt(String spt, RenderedMessage message) {
        String base = "https://" + ALLOWED_HOST + SEND_PATH + "/" + spt + "/";
        String content = NotifyMessageFactory.truncateChars(message.text(), MAX_SPT_CONTENT_CHARS);
        String url = base + URLEncoder.encode(content, StandardCharsets.UTF_8);

        NotifyHttpResponse response = http.get(url);

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            return SendResult.failed(response.statusCode(), response.body());
        }
        return judge(response.body());
    }

    private SendResult sendViaApp(ChannelConfig config, RenderedMessage message) {
        String appToken = config.require("appToken");
        List<String> uids = splitCsv(config.str("uids"));
        List<String> topicIds = splitCsv(config.str("topicIds"));

        if (uids.isEmpty() && topicIds.isEmpty()) {
            throw new MissingConfigException(
                    "WxPusher：至少要填一个 uid 或 topicId，否则这条消息没有任何收件人");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("appToken", appToken);
        payload.put("content", NotifyMessageFactory.truncateChars(message.text(), MAX_CONTENT_CHARS));
        // contentType=1 是纯文本
        payload.put("contentType", 1);

        if (!uids.isEmpty()) {
            ArrayNode array = payload.putArray("uids");
            uids.forEach(array::add);
        }
        if (!topicIds.isEmpty()) {
            ArrayNode array = payload.putArray("topicIds");
            // topicIds 是数字，官方限制单请求 ≤ 5 个
            topicIds.stream().limit(5).forEach(id -> {
                try {
                    array.add(Integer.parseInt(id.trim()));
                } catch (NumberFormatException e) {
                    throw new MissingConfigException("WxPusher：topicId 必须是数字，收到 " + id);
                }
            });
        }

        NotifyHttpResponse response =
                http.postJson("https://" + ALLOWED_HOST + SEND_PATH, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        return judge(response.body());
    }

    /** 成功判定是 {@code code == 1000}，不是 HTTP 200 —— 官方接口 HTTP 恒 200。 */
    private SendResult judge(String body) {
        if (body == null || body.isBlank()) {
            return SendResult.failed(200, "响应体为空");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("code");
            if (code != null && code.asInt() == 1000) {
                return SendResult.ok(200, body);
            }
            return SendResult.failed(200, body);
        } catch (Exception e) {
            log.warn("WxPusher 响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return SendResult.failed(200, body);
        }
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
