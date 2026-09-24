package com.smsgateway.service.notify;

/**
 * 告警「主体」的构造：这条告警是关于谁的。
 *
 * <p>它是去重键（{@link AlertGate}）与规则匹配（{@link AlertRuleEngine}）共用的那一半。
 * 两种形态，前缀区分：
 *
 * <ul>
 *   <li>{@code device:<设备业务标识>} —— 一台设备。**用业务标识而不是主键**：
 *       规则表里管理员填的就是业务标识（设备列表上显示的那一串），用主键就得在匹配时
 *       多做一次 pk→code 的查询；而且业务标识在设备行被删掉之后仍然认得出来
 *       （与 {@code event_log.device_code} 是同一条理由）。</li>
 *   <li>{@code channel:<渠道主键>} —— 一个转发渠道。渠道没有业务标识，只有主键。</li>
 * </ul>
 *
 * <p>限定设备的规则只对 {@code device:} 主体生效 —— 见 {@link AlertRuleEngine}：
 * 让「只为某台设备配的渠道」去收一条渠道告警，属于把告警发到了错误的地方。
 */
public final class AlertSubjects {

    private static final String DEVICE_PREFIX = "device:";
    private static final String CHANNEL_PREFIX = "channel:";

    private AlertSubjects() {
    }

    public static String device(String deviceCode) {
        return DEVICE_PREFIX + (deviceCode == null ? "" : deviceCode);
    }

    public static String channel(Long channelId) {
        return CHANNEL_PREFIX + (channelId == null ? "" : channelId);
    }

    /** 这个主体是不是一台设备。给规则匹配用。 */
    public static boolean isDevice(String subjectKey) {
        return subjectKey != null && subjectKey.startsWith(DEVICE_PREFIX);
    }

    /** 取出设备业务标识；不是设备主体时返回 null。 */
    public static String deviceCodeOf(String subjectKey) {
        return isDevice(subjectKey) ? subjectKey.substring(DEVICE_PREFIX.length()) : null;
    }
}
