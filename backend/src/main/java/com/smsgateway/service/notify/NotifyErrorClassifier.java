package com.smsgateway.service.notify;

import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 判断一次投递失败该不该重试。
 *
 * <p><b>这是整个转发功能里最容易写错、后果也最重的一块。</b>盲目重试会被对端永久封禁 ——
 * Slack 官方原文警告「超限后继续发送可能导致应用被永久禁用」，钉钉超限直接罚 10 分钟。
 * 而这些惩罚落在**渠道凭证**上，不是落在某一条消息上，代价远超「这条没发出去」。
 *
 * <p>所以分类不是「5xx 重试、4xx 不重试」这么粗：
 * <ul>
 *   <li>429 与限流码**可重试**，但退避要更长（对端明确要求你慢下来）</li>
 *   <li>401/403、签名失败（飞书 19021、钉钉 310000）是**配置错**，重试无意义</li>
 *   <li>410 Gone、机器人被移出群是**永久错**，还要顺带把渠道停掉</li>
 *   <li>企微 42001（token 过期）**可重试一次**，前提是先强刷 token</li>
 *   <li>企微 invaliduser/unlicenseduser 是**部分成功**，不重试（重试也不会成功）</li>
 * </ul>
 */
@Slf4j
@Component
public class NotifyErrorClassifier {

    /** 各渠道用错误**文本**表达限流的情况（响应体里出现这些词就按限流处理）。 */
    private static final Pattern RATE_LIMIT_HINT = Pattern.compile(
            "(?i)(rate.?limit|too.?many.?requests|freq|限流|频率|超过限制|qps|exceed)");

    /** 配置错的文本信号：签名不对、token 无效。 */
    private static final Pattern CONFIG_ERROR_HINT = Pattern.compile(
            "(?i)(sign|签名|invalid.?token|token.?expired|access_token|invalid.?credential|unauthorized)");

    /** 永久错：对方已经不接受这个钩子了。 */
    private static final Pattern PERMANENT_HINT = Pattern.compile(
            "(?i)(no_service|no_active_hooks|invalid_payload|action_prohibited|gone|"
                    + "not.?found|deleted|移除|不存在)");

    public enum RetryDecision {
        /** 退避后重试。 */
        RETRY,
        /** 不重试，直接终态（DEAD），需要人介入。 */
        TERMINAL,
        /** 不重试，且**自动停用渠道** —— 这个钩子已经废了，再发只会继续失败。 */
        TERMINAL_DISABLE_CHANNEL
    }

    public RetryDecision classify(SendResult result) {
        // 1. 网络层没到达：连不上、超时、DNS —— 都值得再试
        if (!result.hasResponse()) {
            return RetryDecision.RETRY;
        }

        int status = result.statusCode();

        // 2. HTTP 状态码
        if (status == 429) {
            return RetryDecision.RETRY;
        }
        if (status == 401 || status == 403) {
            return RetryDecision.TERMINAL;
        }
        if (status == 410) {
            // Gone：钩子被删了
            return RetryDecision.TERMINAL_DISABLE_CHANNEL;
        }
        if (status >= 500) {
            return RetryDecision.RETRY;
        }

        // 3. 渠道自己给出的业务错误码 / 文本
        String body = result.body() == null ? "" : result.body();

        if (PERMANENT_HINT.matcher(body).find()) {
            return RetryDecision.TERMINAL_DISABLE_CHANNEL;
        }
        if (RATE_LIMIT_HINT.matcher(body).find()) {
            return RetryDecision.RETRY;
        }
        if (CONFIG_ERROR_HINT.matcher(body).find()) {
            return RetryDecision.TERMINAL;
        }

        // 4. 剩下的 4xx：对端明确拒绝了这一次请求，且没给出我们能识别的信号。
        //    按不重试处理 —— 重试一个语义上被拒绝的请求，比漏发一条更危险。
        if (status >= 400) {
            log.warn("未识别的 4xx（{}），按不可重试处理。响应体前 200 字：{}",
                    status, NotifyRedactor.truncate(body, 200));
            return RetryDecision.TERMINAL;
        }

        // 5. 2xx 但渠道判定失败（各 Sender 已把业务码翻成 success=false）
        return RetryDecision.TERMINAL;
    }

    /** 429 要退避得比普通失败更久：对端明确要求你慢下来。 */
    public boolean isRateLimited(SendResult result) {
        if (result.hasResponse() && result.statusCode() == 429) {
            return true;
        }
        String body = result.body() == null ? "" : result.body();
        return RATE_LIMIT_HINT.matcher(body).find();
    }

    /** 给一次「请求确实发出去了」的结果构造分类输入，便于单测直接调。 */
    public static NotifyHttpResponse response(int status, String body) {
        return new NotifyHttpResponse(status, body, null);
    }
}
