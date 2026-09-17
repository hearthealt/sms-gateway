package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @NotBlank(message = "deviceId cannot be empty")
    private String deviceId;

    private String deviceName;

    /**
     * Android 客户端字段名为 phone，此处兼容两种写法。
     */
    @JsonAlias("phone")
    private String phoneNumber;

    /**
     * 以下遥测字段 Android 端一直在上报，此前 DTO 未接收导致被静默丢弃。
     */
    private Integer battery;

    private String network;

    private Boolean charging;

    private Integer pendingCount;
}