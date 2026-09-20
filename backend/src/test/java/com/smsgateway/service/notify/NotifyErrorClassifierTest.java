package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类。
 *
 * <p>这组测试守的是一条具体的攻击面：**盲目重试会被对端永久封禁**。
 * Slack 官方警告「超限后继续发送可能导致应用被永久禁用」，钉钉超限罚 10 分钟 ——
 * 惩罚都落在渠道凭证上，不是落在某一条消息上。
 */
class NotifyErrorClassifierTest {

    private final NotifyErrorClassifier classifier = new NotifyErrorClassifier();

    @Test
    @DisplayName("网络层没到达 → 重试（超时、连接被拒都值得再试）")
    void networkFailureIsRetryable() {
        SendResult result = SendResult.networkError("ConnectException: Connection refused");
        assertThat(classifier.classify(result)).isEqualTo(NotifyErrorClassifier.RetryDecision.RETRY);
    }

    @Test
    @DisplayName("5xx → 重试")
    void serverErrorIsRetryable() {
        assertThat(classifier.classify(SendResult.failed(500, "")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.RETRY);
        assertThat(classifier.classify(SendResult.failed(503, "")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.RETRY);
    }

    @Test
    @DisplayName("429 → 重试")
    void rateLimitIsRetryable() {
        assertThat(classifier.classify(SendResult.failed(429, "too many requests")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.RETRY);
    }

    @Test
    @DisplayName("401 / 403 → 终态。配置错，重试一百次也不会变对")
    void authErrorIsTerminal() {
        assertThat(classifier.classify(SendResult.failed(401, "")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL);
        assertThat(classifier.classify(SendResult.failed(403, "")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL);
    }

    @Test
    @DisplayName("410 Gone → 终态并停用渠道。这个钩子已经废了")
    void goneDisablesChannel() {
        assertThat(classifier.classify(SendResult.failed(410, "")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL_DISABLE_CHANNEL);
    }

    @Test
    @DisplayName("Slack 的 no_active_hooks / no_service 文本 → 停用渠道")
    void slackPermanentErrorsDisableChannel() {
        assertThat(classifier.classify(SendResult.failed(400, "{\"error\":\"no_active_hooks\"}")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL_DISABLE_CHANNEL);
        assertThat(classifier.classify(SendResult.failed(404, "{\"error\":\"no_service\"}")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL_DISABLE_CHANNEL);
    }

    @Test
    @DisplayName("签名失败 / token 无效 → 终态（不重试）")
    void signatureFailureIsTerminal() {
        assertThat(classifier.classify(SendResult.failed(200, "{\"errcode\":19021,\"errmsg\":\"sign match fail\"}")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL);
    }

    @Test
    @DisplayName("企微 errmsg 里的「频率限制」→ 重试，哪怕 HTTP 是 200")
    void wecomRateLimitTextIsRetryable() {
        // 企微的 HTTP 永远 200，成败在 errcode/errmsg 里 ——
        // 只看状态码会把限流当成永久失败，把渠道直接判死。
        assertThat(classifier.classify(SendResult.failed(200, "{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.RETRY);
    }

    @Test
    @DisplayName("没识别的 4xx → 终态。重试一个语义上被拒绝的请求比漏发更危险")
    void unknownClientErrorIsTerminal() {
        assertThat(classifier.classify(SendResult.failed(418, "I'm a teapot")))
                .isEqualTo(NotifyErrorClassifier.RetryDecision.TERMINAL);
    }

    @Test
    @DisplayName("isRateLimited 认 429，也认响应体里的限流文案")
    void detectsRateLimit() {
        assertThat(classifier.isRateLimited(SendResult.failed(429, ""))).isTrue();
        assertThat(classifier.isRateLimited(SendResult.failed(200, "{\"errmsg\":\"频率限制\"}"))).isTrue();
        assertThat(classifier.isRateLimited(SendResult.failed(500, ""))).isFalse();
    }
}
