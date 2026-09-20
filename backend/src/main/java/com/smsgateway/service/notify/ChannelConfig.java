package com.smsgateway.service.notify;

import java.util.Collections;
import java.util.Map;

/**
 * 解密后的渠道配置。
 *
 * <p>用 Map 而不是为每种渠道建一个强类型类：渠道类型会长到十几种，每加一种就建一个
 * DTO 加一套 Jackson 映射，而它们之间**没有共享的结构**（企微只要一个 URL，
 * 通用 webhook 要 URL + 密钥 + SSRF 开关）。真正需要强类型的是发送时的入参，
 * 那个由各 Sender 自己收敛。
 *
 * <p>取不到值时一律给默认值而不是抛异常 —— 配置是管理员手填的，缺字段是常态，
 * 应当在投递时被归成「配置错、不可重试」并写进渠道的 last_error，而不是让
 * 整个调度线程崩掉。
 */
public record ChannelConfig(Map<String, Object> values) {

    public static ChannelConfig of(Map<String, Object> values) {
        return new ChannelConfig(values == null ? Collections.emptyMap() : values);
    }

    public String str(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    public String str(String key, String defaultValue) {
        String value = str(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    public boolean bool(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    /** 必填字段缺失时抛它 —— 会被归成「配置错、不重试」。 */
    public String require(String key) {
        String value = str(key);
        if (value == null || value.isEmpty()) {
            throw new MissingConfigException("渠道配置缺少必填项：" + key);
        }
        return value;
    }
}
