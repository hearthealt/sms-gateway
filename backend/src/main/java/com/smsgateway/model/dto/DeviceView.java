package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理后台设备视图。字段名对齐前端 types/index.ts 的 Device。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceView {

    private Long id;
    private String deviceId;
    private String deviceName;
    private String phone;
    private String platform;
    private String appVersion;

    /**
     * 展示用状态，取值 online / offline / DISABLED。
     * 前两者表示可达性，DISABLED 表示已被管理员禁用。
     */
    private String status;

    /** 管理员可控的启用状态，与 status 是两个维度。 */
    private boolean enabled;

    private LocalDateTime lastHeartbeat;
    private Integer battery;
    private String network;
    private Boolean charging;
    private Integer pendingCount;
    private LocalDateTime createTime;
}
