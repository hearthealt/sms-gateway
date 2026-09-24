package com.smsgateway.model.enums;

/**
 * 一条投递记录的载荷来源。
 *
 * <p>这张表是**投递链路**，不是「短信表」：短信与告警共用退避、限流、卡死回收、
 * 连续失败自动停用、脱敏这一整套，另起一张表 + 第二套调度器要复制约 400 行并发代码，
 * 而两份必然分叉。代价就是这一列 —— 读取方按它决定「去哪儿取正文」。
 *
 * <p>默认 {@link #SMS}，于是本次变更之前的老行不需要任何数据迁移。
 */
public enum NotifyDeliverySource {

    SMS("短信"),

    ALERT("故障告警");

    private final String label;

    NotifyDeliverySource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
