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
 * 钉钉自定义机器人。
 *
 * <p>官方要点与三个最常踩的坑：
 * <ul>
 *   <li>URL 形如 {@code https://oapi.dingtalk.com/robot/send?access_token=<TOKEN>}。
 *       🚨 这里的 {@code access_token} 是 **webhook 的 token，不是应用的 access_token** ——
 *       官方在 Java SDK 示例里专门注释强调过，是最常见的混淆点。</li>
 *   <li>加签的 {@code timestamp}（**毫秒**，飞书是秒）与 {@code sign} 必须拼在
 *       <b>URL query</b> 上（飞书是放 body），否则得到 {@code 310000}。</li>
 *   <li><b>创建机器人时必须至少配一种安全设置</b>（关键词 / 加签 / IP 白名单），
 *       否则直接拒。若用关键词方式，转发内容里必须包含那个词 —— 默认消息以
 *       「【短信转发】」开头，把关键词设成「短信转发」即可。</li>
 * </ul>
 *
 * <p>限流 20 条/分钟，**超过会限流 10 分钟**（比企微的惩罚重），所以默认值取 15 留余量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkBotSender implements ChannelSender {

    private static final String ALLOWED_HOST = "oapi.dingtalk.com";

    /**
     * markdown 的 {@code title} 字段。
     *
     * <p>它不是消息体标题，而是**会话列表里透出的那一行**，且官方要求必填。
     */
    private static final String MARKDOWN_TITLE = "短信转发";

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.DINGTALK_BOT;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方硬限 20，超限罚 10 分钟 —— 留 5 条余量买平安，代价只是晚几秒发
        return 15;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String url = config.require("webhookUrl");
        ChannelEndpoints.requireHost(url, "钉钉机器人", ALLOWED_HOST);

        String secret = config.str("secret");
        if (secret != null && !secret.isBlank()) {
            // 拼在 URL 上 —— 这条与飞书相反，放 body 会拿到 310000
            url = BotSignatures.dingtalkSignedUrl(url, secret);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("msgtype", "markdown");
        ObjectNode markdown = payload.putObject("markdown");
        markdown.put("title", MARKDOWN_TITLE);
        // 钉钉的 markdown 不支持表格、代码块、分割线，比企微的 markdown_v2 弱得多。
        // 我们只发纯文本 + 换行，不碰那几样。
        markdown.put("text", message.text());

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            return SendResult.failed(response.statusCode(), response.body());
        }

        Integer errcode = parseErrcode(response.body());
        if (errcode != null && errcode == 0) {
            return SendResult.ok(200, response.body());
        }

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
            log.warn("钉钉响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return null;
        }
    }
}
