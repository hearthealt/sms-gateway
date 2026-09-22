package com.smsgateway.model.enums;

/**
 * 运行事件的类型。
 *
 * <p>这张清单要回答的是一个很具体的问题：**「这条短信在服务端这一侧到底怎么了？」**
 * 起因是一次静默丢失 —— 设备说没传上去、服务端说没收到，两边都查不到。
 * 所以「上报的结果」那几条（存下 / 重复 / 被规则忽略 / 被拒）是核心，
 * 其余是设备身份与在线状态的变动，它们解释了「为什么传不上来」。
 *
 * <p><b>label 带在枚举上</b>（与 {@code SysConfigKey} 同一个做法）：管理端的中文
 * 从后端取，前端不硬编码 —— 否则加一个类型就要改两处，而漏掉的那处表现是
 * 界面上冒出一串英文常量名。
 *
 * <p><b>这张表里不放正文与验证码</b>（见 {@code schema.sql} 的 event_log 表注释）。
 * 要看内容按 sms_message_id 关联回 sms_message。
 */
public enum EventType {

    // ---------------------------------------------------------------- 上报结果

    SMS_STORED("存下", EventLevel.INFO),
    SMS_DUPLICATE("重复", EventLevel.INFO),
    SMS_IGNORED_BY_RULE("被采集规则忽略", EventLevel.WARN),
    SMS_REJECTED_DEVICE_DISABLED("上报被拒（设备已禁用）", EventLevel.WARN),

    // ---------------------------------------------------------------- 设备身份

    DEVICE_TOKEN_INVALID("令牌无效", EventLevel.ERROR),
    DEVICE_REGISTERED("注册成功", EventLevel.INFO),
    DEVICE_RE_REGISTERED("重新注册", EventLevel.INFO),
    DEVICE_ENROLL_REJECTED("注册被拒", EventLevel.WARN),

    // ---------------------------------------------------------------- 在线状态

    DEVICE_ONLINE("设备上线", EventLevel.INFO),
    DEVICE_OFFLINE("设备离线", EventLevel.WARN),
    DEVICE_OFFLINE_REPORTED("设备主动报停", EventLevel.INFO),

    // ---------------------------------------------------------------- 管理动作

    DEVICE_DISABLED_BY_ADMIN("管理员禁用设备", EventLevel.WARN),
    DEVICE_ENABLED_BY_ADMIN("管理员启用设备", EventLevel.INFO),
    DEVICE_DELETED("设备被删除", EventLevel.WARN),

    // ---------------------------------------------------------------- 转发渠道

    CHANNEL_AUTO_DISABLED("渠道自动停用", EventLevel.ERROR);

    private final String label;
    private final EventLevel defaultLevel;

    EventType(String label, EventLevel defaultLevel) {
        this.label = label;
        this.defaultLevel = defaultLevel;
    }

    public String label() {
        return label;
    }

    /**
     * 这类事件默认的级别。
     *
     * <p>只是「默认」：写事件时仍可以显式指定级别。留这个默认值是为了让
     * {@code EventLogService.record(type, ...)} 那种只关心「发生了什么」的调用点
     * 不必每次都重复写一遍级别，也保证同一个类型不会因为漏写而时高时低。
     */
    public EventLevel defaultLevel() {
        return defaultLevel;
    }
}
