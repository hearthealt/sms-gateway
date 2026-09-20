package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 企业微信群机器人。
 *
 * <p>选它当第一期首选渠道的理由：一个 URL 即全部凭证，没有签名、没有 token 生命周期、
 * 不需要企业主体，而 20 条/分钟对个人短信网关完全够用，且官方合规稳定。
 *
 * <p>官方文档要点（实现以此为准，别凭印象改）：
 * <ul>
 *   <li>URL 形如 {@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<KEY>}，
 *       {@code key} 就是全部凭据</li>
 *   <li><b>HTTP 状态码永远返回 200</b>，成败在响应体的 {@code errcode} 里 ——
 *       只看状态码会把所有失败都记成成功</li>
 *   <li>限流 20 条/分钟</li>
 *   <li>{@code markdown} 按 <b>UTF-8 字节</b>限长 4096，且超长**静默截断不报错**</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomBotSender implements ChannelSender {

    /**
     * 只认官方域名。
     *
     * <p>OWASP 明确说黑名单可绕过、优先用白名单。企微是**固定对接方**，
     * 白名单加得起来就不该只依赖 {@link SsrfGuard} 那份黑名单 ——
     * 黑名单拦不住「攻击者注册一个公网域名、用 A 记录指向内网」以外的花样，
     * 而白名单根本不给填别的域名的机会。
     */
    private static final String ALLOWED_HOST = "qyapi.weixin.qq.com";

    /** 官方限制：markdown 内容 ≤ 4096 字节。 */
    private static final int MAX_CONTENT_BYTES = 4096;

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.WECOM_BOT;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方硬限就是 20，没有留余地的空间（也不该留：留了就是白丢投递机会）
        return 20;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String url = config.require("webhookUrl");

        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new MissingConfigException("企微 webhook 地址格式不合法：" + e.getMessage());
        }
        if (!ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new MissingConfigException(
                    "企微群机器人的地址必须是 https://" + ALLOWED_HOST + "/… ，当前填的是："
                            + NotifyRedactor.safeEndpoint(url));
        }

        String content = NotifyMessageFactory.truncateUtf8(message.text(), MAX_CONTENT_BYTES);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("msgtype", "markdown");
        payload.putObject("markdown").put("content", content);

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            return SendResult.failed(response.statusCode(), response.body());
        }

        // 到这里 HTTP 是 200，但企微的失败也藏在这里面
        Integer errcode = parseErrcode(response.body());
        if (errcode != null && errcode == 0) {
            return SendResult.ok(200, response.body());
        }

        // errcode 不为 0（或响应体解不出来）都算失败。把原始响应带回去，
        // 由 NotifyErrorClassifier 按 errmsg 里的关键词分类 ——
        // 企微的错误文案（如「频率限制」）比 errcode 数字更能说明该不该重试。
        return SendResult.failed(200, response.body());
    }

    private Integer parseErrcode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode errcode = node.get("errcode");
            return errcode == null ? null : errcode.asInt();
        } catch (Exception e) {
            log.warn("企微响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return null;
        }
    }
}
