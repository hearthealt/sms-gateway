package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 企业微信应用消息 —— 推到企业微信里的**个人**（不是群）。
 *
 * <p>比群机器人重得多：需要企业主体、自建应用、维护 access_token 生命周期
 * （见 {@link WecomTokenManager}）。但如果已经有企业微信组织，这是合规且可控的方案。
 *
 * <p>官方要点：
 * <ul>
 *   <li>换 token：{@code GET /cgi-bin/gettoken?corpid=&corpsecret=}，{@code expires_in: 7200}</li>
 *   <li>发消息：{@code POST /cgi-bin/message/send?access_token=...}</li>
 *   <li>限流：**每应用对同一个成员不超过 30 次/分钟、1000 次/小时**，超过部分被丢弃</li>
 *   <li>🚨 返回 {@code invaliduser} / {@code unlicenseduser} 时**发送仍然执行**（部分成功）。
 *       网关必须解析这两个字段判断真实投递情况，**不能只看 {@code errcode}**</li>
 *   <li>{@code unlicenseduser} = 用户在可见范围内但**没有基础接口许可**（收费项）</li>
 *   <li>文本 ≤ 2048 字节，**超长截断**</li>
 *   <li>{@code enable_duplicate_check} 默认 1800 秒内相同内容不重复下发 ——
 *       对重试场景是双刃剑，**必须显式设 0**，否则我们的重试会被它静默吞掉</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomAppSender implements ChannelSender {

    private static final String ALLOWED_HOST = "qyapi.weixin.qq.com";
    private static final String SEND_PATH = "/cgi-bin/message/send";

    /** 官方限制文本 ≤ 2048 字节。 */
    private static final int MAX_CONTENT_BYTES = 2048;

    /** 42001 = access_token 过期（或被企微提前作废）。 */
    private static final int ERRCODE_TOKEN_EXPIRED = 42001;

    private final NotifyHttpClient http;
    private final WecomTokenManager tokenManager;
    private final ObjectMapper objectMapper;

    @Override
    public NotifyChannelType type() {
        return NotifyChannelType.WECOM_APP;
    }

    @Override
    public int defaultRateLimitPerMin() {
        // 官方：每应用对同一个成员 30 次/分钟。转发一般只发给一两个人，取 30
        return 30;
    }

    @Override
    public SendResult send(ChannelConfig config, RenderedMessage message) {
        String corpId = config.require("corpId");
        String corpSecret = config.require("corpSecret");
        String agentId = config.require("agentId");
        // 默认发给所有人：配应用时最常见的诉求就是「推给我自己」，
        // 而 @all 在企业微信里就是「应用可见范围内的所有人」
        String touser = config.str("touser", "@all");

        SendResult result = doSend(
                tokenManager.getToken(corpId, corpSecret), agentId, touser, message);

        // 42001 → 强刷 token 重试**一次**。
        // 只重试一次是官方口径，也是安全上限：如果刷完还是 42001，那问题不在 token 上，
        // 再刷只会把 gettoken 的调用频率打上去（而频繁调 gettoken 会被拦截）。
        if (isTokenExpired(result)) {
            log.info("企业微信 token 已失效（42001），强刷后重试一次");
            tokenManager.evict(corpId, corpSecret);
            result = doSend(tokenManager.getToken(corpId, corpSecret), agentId, touser, message);
        }

        return result;
    }

    private SendResult doSend(String accessToken, String agentId, String touser, RenderedMessage message) {
        String url = "https://" + ALLOWED_HOST + SEND_PATH
                + "?access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        ChannelEndpoints.requireHost(url, "企业微信应用", ALLOWED_HOST);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("touser", touser);
        payload.put("msgtype", "text");
        payload.put("agentid", agentId);
        payload.putObject("text")
                .put("content", NotifyMessageFactory.truncateUtf8(message.text(), MAX_CONTENT_BYTES));
        // **必须显式设 0**：默认 1800 秒内相同内容不重复下发，而「重试」发的正是
        // 相同内容 —— 不关掉的话，我们的重试会被它静默吞掉，投递记录写着成功、
        // 用户却从来没收到
        payload.put("enable_duplicate_check", 0);

        NotifyHttpResponse response = http.postJson(url, payload.toString());

        if (!response.hasResponse()) {
            return SendResult.networkError(response.error());
        }
        if (response.statusCode() != 200) {
            return SendResult.failed(response.statusCode(), response.body());
        }
        return judge(response.body());
    }

    /**
     * 判定真实投递情况。
     *
     * <p><b>不能只看 {@code errcode}</b>：官方明确说返回 {@code invaliduser} /
     * {@code unlicenseduser} 时发送**仍然执行**（部分成功）。只看 errcode 会把
     * 「有几个人没收到」记成完全成功。
     */
    private SendResult judge(String body) {
        if (body == null || body.isBlank()) {
            return SendResult.failed(200, "响应体为空");
        }

        try {
            JsonNode node = objectMapper.readTree(body);
            int errcode = node.path("errcode").asInt(-1);

            if (errcode != 0) {
                return SendResult.failed(200, body);
            }

            String invalidUser = node.path("invaliduser").asText("");
            String unlicensedUser = node.path("unlicenseduser").asText("");

            if (!invalidUser.isBlank() || !unlicensedUser.isBlank()) {
                // 部分成功。归为**失败但不重试**：重试也不会成功（一个是用户不在可见范围、
                // 一个是没买基础接口许可），而且会浪费配额。分类器看到 200 + 这段文案
                // 会归到 TERMINAL，不再重试。
                log.warn("企业微信部分投递失败：invaliduser={}, unlicenseduser={}",
                        invalidUser, unlicensedUser);
                return SendResult.failed(200,
                        "部分成员未收到：invaliduser=" + invalidUser + ", unlicenseduser=" + unlicensedUser);
            }

            return SendResult.ok(200, body);
        } catch (Exception e) {
            log.warn("企业微信响应体不是合法 JSON，按失败处理：{}", NotifyRedactor.truncate(body, 200));
            return SendResult.failed(200, body);
        }
    }

    private boolean isTokenExpired(SendResult result) {
        if (result.success() || result.body() == null) {
            return false;
        }
        try {
            return objectMapper.readTree(result.body()).path("errcode").asInt(-1) == ERRCODE_TOKEN_EXPIRED;
        } catch (Exception e) {
            return false;
        }
    }
}
