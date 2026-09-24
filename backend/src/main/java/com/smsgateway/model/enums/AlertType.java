package com.smsgateway.model.enums;

/**
 * 故障告警的类型。
 *
 * <p>「失败可见性」是这个特性存在的全部理由：设备离线、渠道连续失败、远程指令没执行成功，
 * 这三件事**原先只写进 event_log**，没有人会主动去翻。而它们恰恰是最需要主动叫人的 ——
 * 一台无人值守的设备离线了，没有任何人知道，直到有人想起来「今天的验证码怎么没来」。
 *
 * <p>中文名挂在枚举上（同 {@link EventType#label()}），管理端从后端取。
 *
 * <p>{@link #DEVICE_COMMAND_FAILED} 是刻意列进来的第三种：远程指令给了管理员
 * 「不看现场就能动手」的能力，而一条没执行成功的指令没人知道，正是这个特性要消灭的
 * 那类静默失败的最直接实例。两条特性在这里合流，而实现成本接近于零（机制已经在了）。
 */
public enum AlertType {

    DEVICE_OFFLINE("设备离线"),

    CHANNEL_AUTO_DISABLED("转发渠道被自动停用"),

    DEVICE_COMMAND_FAILED("远程指令失败");

    private final String label;

    AlertType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
