package com.smsgateway.service.notify;

/**
 * 目标地址没通过 SSRF 校验。
 *
 * <p>单独一个类型是为了让投递层能把它归到「不可重试」：同一个地址重试一百次，
 * 解析结果不会变，重试只是在浪费配额并推迟后面真正能发出去的短信。
 */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String message) {
        super(message);
    }
}
