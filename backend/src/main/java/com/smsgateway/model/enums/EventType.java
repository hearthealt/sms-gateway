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

    // ---------------------------------------------------------------- 远程指令

    // 「下发」与「已执行」是两条独立的事件，不是一条的两个字段：
    // 「发了但设备一直没回执」和「执行了但失败了」在现场是完全不同的问题，
    // 而后者又分成「设备说做不了」与「设备压根没收到」。
    DEVICE_COMMAND_ISSUED("下发远程指令", EventLevel.INFO),
    DEVICE_COMMAND_ACKED("远程指令已执行", EventLevel.INFO),
    DEVICE_COMMAND_FAILED("远程指令执行失败", EventLevel.WARN),
    DEVICE_COMMAND_EXPIRED("远程指令已过期", EventLevel.WARN),
    DEVICE_COMMAND_CANCELLED("撤销远程指令", EventLevel.INFO),

    // ---------------------------------------------------------------- 外发短信

    // 「入队」与「已发出」是两条独立事件：前者只说明我们把它交给了设备，
    // 后者才说明那条短信真的离开了手机 —— 而这条短信是要计费的。
    // 只见 QUEUED 不见后面那条，就是「结果未知」的样子。
    SMS_OUTBOUND_QUEUED("外发短信已入队", EventLevel.INFO),
    SMS_OUTBOUND_SENT("外发短信已发出", EventLevel.INFO),
    SMS_OUTBOUND_FAILED("外发短信发送失败", EventLevel.WARN),
    SMS_OUTBOUND_UNKNOWN("外发短信结果未知", EventLevel.WARN),
    SMS_OUTBOUND_CANCELLED("撤销外发短信", EventLevel.INFO),

    // ---------------------------------------------------------------- 转发渠道

    CHANNEL_AUTO_DISABLED("渠道自动停用", EventLevel.ERROR),

    // ---------------------------------------------------------------- 故障告警

    /**
     * 有告警要发，但一条能用的渠道都没有。
     *
     * <p>**这是最危险的静默状态**：唯一一个渠道挂了，于是「渠道挂了」这件事没有人知道。
     * 它是告警链路上唯一能让人发现「告警本身发不出去」的地方，所以是 ERROR 级。
     */
    ALERT_UNDELIVERABLE("告警无处可发", EventLevel.ERROR),

    /**
     * 一条告警自己走到了投递终点（重试次数用尽或对端明确拒绝）。
     *
     * <p>这是最深的一层失败：告警本来就是为了「别静默失败」而存在的，
     * 而它自己静默地没发出去。短信走到 DEAD 不记这条（那在投递记录页上是常态，
     * 见 NotifyDispatcher.markDead），告警走 DEAD 必须记。
     */
    ALERT_DELIVERY_DEAD("告警投递放弃", EventLevel.ERROR);

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
