package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则的管理端视图。
 *
 * <p>{@code alertTypeLabel} 由服务端给（枚举上带中文），前端不硬编码 —— 与
 * {@code NotifyChannelView} / {@code DeviceCommandView} 同一个做法。类型为 null 时
 * 给「不限类型」而不是 null：界面上一格空白看不出是「不限」还是「没读到」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleView {

    private Long id;

    private String ruleName;

    /** null = 不限类型。 */
    private String alertType;

    private String alertTypeLabel;

    /** null = 不限设备。 */
    private String deviceId;

    private boolean enabled;

    private List<Long> channelIds;

    /**
     * 与 {@code channelIds} **一一对应**的渠道名，查不到的给「（渠道已删除）」占位。
     *
     * <p>与转发规则的视图同一个做法与同一个理由：跳过查不到的会让前端按下标对不上，
     * 显示成「运维群」的其实是另一个渠道。停用的渠道也标出来（加「（已停用）」）——
     * 规则配着它，但它实际不会投递，而规则页上看不出来。
     */
    private List<String> targetChannelNames;

    private LocalDateTime createTime;
}
