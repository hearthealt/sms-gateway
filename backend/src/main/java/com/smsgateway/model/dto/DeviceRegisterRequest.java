package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegisterRequest {

    @NotBlank(message = "deviceId cannot be empty")
    private String deviceId;

    private String deviceName;

    /**
     * Android 客户端字段名为 phone，此处兼容两种写法，
     * 否则手机号会因字段名不匹配而始终为 null。
     */
    @JsonAlias("phone")
    private String phoneNumber;

    private String platform;

    @JsonAlias("appVersion")
    private String appVersion;
}