package com.smsgateway.service.notify;

/**
 * 一条已经拼好、可以发出去的消息。
 *
 * <p>{@code text} 就是**短信原文**加上一行元信息，见 {@link NotifyMessageFactory}。
 * 没有「原始验证码 / 打码后验证码」这种字段 —— 不打码，所以不存在这个区分。
 *
 * @param text       要发出去的正文
 * @param smsMessageId 关联回 sms_message，便于排查
 * @param time       已格式化的接收时间
 */
public record RenderedMessage(
        String text,
        Long smsMessageId,
        String sender,
        String phone,
        String deviceName,
        String time
) {
}
