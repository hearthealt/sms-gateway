package com.smsgateway.service.notify;

/**
 * 渠道配置缺字段 / 字段非法。
 *
 * <p>归到「不可重试」：配置错不会因为多试几次就变对，重试只是在浪费对端配额，
 * 并把后面真正能发出去的短信一起推迟。要人去管理端改。
 */
public class MissingConfigException extends RuntimeException {

    public MissingConfigException(String message) {
        super(message);
    }
}
