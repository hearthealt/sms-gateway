package com.smsgateway.model.enums;

public enum SmsStatus {
    RECEIVED,
    DUPLICATE,
    PROCESSED,
    /** 命中采集规则的 ignore 动作：保留数据便于回溯，但不参与验证码缓存与推送。 */
    IGNORED
}