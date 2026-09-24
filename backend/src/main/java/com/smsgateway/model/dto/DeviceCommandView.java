package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端的指令视图。
 *
 * <p>{@code typeLabel} / {@code statusLabel} 由服务端给（枚举上带中文），
 * 前端不硬编码 —— 与 {@code EventType.label()}、{@code NotifyChannelType} 同一个做法。
 * 前端自己维护一份映射的话，加一种指令就会在界面上冒出一串英文常量名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandView {

    private Long id;

    /** 设备业务标识。 */
    private String deviceId;

    private String deviceName;

    /** 见 DeviceCommandType 枚举。 */
    private String type;

    private String typeLabel;

    private String argument;

    /** 见 DeviceCommandStatus 枚举。 */
    private String status;

    private String statusLabel;

    /** 已下发次数。> 1 说明设备一直没回执，这本身就是排查线索。 */
    private int attempts;

    private LocalDateTime nextDeliverAt;

    private LocalDateTime sentAt;

    private LocalDateTime expiresAt;

    private LocalDateTime ackedAt;

    private String resultDetail;

    /** 签发这条指令的管理员账号。 */
    private String issuedBy;

    private LocalDateTime createTime;
}
