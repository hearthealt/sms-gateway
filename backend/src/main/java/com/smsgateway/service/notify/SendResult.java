package com.smsgateway.service.notify;

/**
 * 一次发送的结果。
 *
 * <p>{@code statusCode} 为 0 表示**请求根本没发出去**（网络层失败或地址非法），
 * 此时 {@code error} 非空。这个区分是必须的：网络层失败一律可重试，
 * 而对端明确回了 4xx 就多半不该重试 —— 看 {@link NotifyErrorClassifier}。
 */
public record SendResult(boolean success, int statusCode, String body, String error) {

    /** 请求确实到了对端并拿到响应。 */
    public boolean hasResponse() {
        return statusCode > 0;
    }

    public static SendResult ok(int statusCode, String body) {
        return new SendResult(true, statusCode, body, null);
    }

    /** 对端回了，但判定为失败（非 2xx，或 2xx 里带着业务错误码）。 */
    public static SendResult failed(int statusCode, String body) {
        return new SendResult(false, statusCode, body, null);
    }

    /** 请求没发出去。 */
    public static SendResult networkError(String error) {
        return new SendResult(false, 0, null, error);
    }

    /**
     * 给**错误摘要**用的一句话。
     *
     * <p>它只是把状态码与响应体拼起来，**成功的结果也会拼出一句「HTTP 200：{...}」** ——
     * 所以只能用在失败路径上。曾经有人无条件拿它去写 {@code last_error}，
     * 结果每条成功记录都挂着一句看着像报错的东西。
     */
    public String describe() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        if (body != null && !body.isBlank()) {
            return "HTTP " + statusCode + "：" + body;
        }
        return "HTTP " + statusCode;
    }
}
