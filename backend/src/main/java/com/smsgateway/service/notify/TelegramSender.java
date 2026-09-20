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
 * Telegram Bot。
 *
 * <p>官方要点与最大的一个坑：
 * <ul>
 *   <li>URL 形如 {@code https://api.telegram.org/bot<TOKEN>/sendMessage}，
 *       token 在**路径**里（所以日志脱敏要认路径上的长随机串，见 {@link NotifyRedactor}）</li>
 *   <li>🚨 <b>不要用 {@code MarkdownV2}。</b>它要求转义 18 个字符
 *       （{@code _ * [ ] ( ) ~ ` &gt; # + - = | { } . !}），而短信正文里
 *       {@code .} {@code -} {@code !} {@code (} {@code )} {@code #} {@code +} {@code =}
 *       极其常见，转义地狱。**用 {@code HTML} 模式，只需转义 {@code & < >} 三个字符。**</li>
 *   <li>text 上限 4096 字符（**按字符**，不是字节）</li>
 *   <li><b>bot 不能主动私聊未 {@code /start} 过的用户</b>（返回 403）——
 *       所以配置 chat_id 前必须先跟这个 bot 说过话</li>
 *   <li>429 响应带 {@code parameters.retry_after}（秒）。这里**没有**精确采用它，
 *       而是由调度器对 429 统一加 30 秒下限 —— 见下</li>
 * </ul>
 *
 * <p>关于 {@code retry_after}：官方建议「优先采用它而不是自己的退避计算」。
 * 本实现没做（要把它从响应体传进退避计算，属于调度层的改动），
 * 当前用「429 至少等 30 秒」近似。Telegram 给的 retry_after 通常也就是几十秒，
 * 量级接近；真要精确，等有实际限流投诉再说。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSender implements ChannelSender {

    private static final String ALLOWED_HOST = "api.telegram.org";

    /** 官方限制 text ≤ 4096 字符（转义后）。 */
    private static final int MAX_TEXT_CHARS = 4000;

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.TELEGRAM_BOT;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 群组 20 条/分钟；单 chat 另有 1 条/秒 的限制
        return 20;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String botToken = config.require("botToken");
        String chatId = config.require("chatId");

        String url = "https://" + ALLOWED_HOST + "/bot" + botToken + "/sendMessage";
        // 校验的是**我们自己拼出来的**地址，用户填的只是 token 与 chatId，
        // 所以这里不存在 SSRF 面（host 是常量）。仍然过一道，防止 token 里被塞进 '/'
        // 之类的东西把路径改掉。
        ChannelEndpoints.requireHost(url, "Telegram", ALLOWED_HOST);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chat_id", chatId);
        payload.put("text", escapeHtml(NotifyMessageFactory.truncateChars(message.text(), MAX_TEXT_CHARS)));
        payload.put("parse_mode", "HTML");

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            // 403 = bot 不能主动私聊（用户没 /start 过）；429 = 限流；
            // 分类器认状态码，这两条各自归到正确的档
            return SendResult.failed(response.statusCode(), response.body());
        }

        if (parseOk(response.body())) {
            return SendResult.ok(200, response.body());
        }
        return SendResult.failed(200, response.body());
    }

    /**
     * HTML 模式只需转义三个字符。
     *
     * <p>这就是选 HTML 而不是 MarkdownV2 的全部理由：18 个字符的转义表 vs 3 个。
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private boolean parseOk(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode ok = node.get("ok");
            return ok != null && ok.asBoolean();
        } catch (Exception e) {
            log.warn("Telegram 响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return false;
        }
    }
}
