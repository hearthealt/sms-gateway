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
 * PushPlus（推到个人微信）。
 *
 * <p>官方要点，其中一条会让「失败判断」整体失真：
 * <ul>
 *   <li>端点 {@code POST http://www.pushplus.plus/send} —— 注意官方文档给的是
 *       <b>http</b>，不是 https</li>
 *   <li>参数 {@code token} + {@code title} + {@code content}（+ 可选 {@code topic}）</li>
 *   <li>成功判定：{@code code == 200}</li>
 *   <li>🚨 <b>接口是异步的</b>：返回 200 只代表「请求已收到」，**不代表已经发出去**。
 *       要确认真实结果得配 {@code callbackUrl} 或查 {@code shortCode}。
 *       本实现拿不到这个信息，所以这里判定的「成功」只到「请求被受理」这一层 ——
 *       写进投递记录时是 SUCCESS，但真实送达可能失败。这是接口本身的性质，不是这里的疏漏。</li>
 *   <li>🚨 <b>失败的错误请求也计数</b>：额度被失败请求吃掉了</li>
 *   <li>未实名 0 条；实名后 1 分钟 5 次 / 200 次/天</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushPlusSender implements ChannelSender {

    private static final String ALLOWED_HOST = "www.pushplus.plus";
    private static final String SEND_URL = "http://" + ALLOWED_HOST + "/send";

    private static final String TITLE = "短信转发";

    private final NotifyHttpClient http;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.PUSHPLUS;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 实名后 1 分钟 5 次 —— 这是比官方「200 次/天」更紧的那条
        return 5;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String token = config.require("token");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("token", token);
        payload.put("title", TITLE);
        payload.put("content", message.text());
        // template=txt 才是纯文本；默认的 html 模板会把换行吃掉、把内容塞进一个卡片
        payload.put("template", "txt");

        String topic = config.str("topic");
        if (topic != null && !topic.isBlank()) {
            payload.put("topic", topic);
        }

        NotifyHttpResponse response = http.postJson(SEND_URL, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        return judge(response.body());
    }

    private SendResult judge(String body) {
        if (body == null || body.isBlank()) {
            return SendResult.failed(200, "响应体为空");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("code");
            if (code != null && code.asInt() == 200) {
                return SendResult.ok(200, body);
            }
            return SendResult.failed(200, body);
        } catch (Exception e) {
            log.warn("PushPlus 响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return SendResult.failed(200, body);
        }
    }
}
