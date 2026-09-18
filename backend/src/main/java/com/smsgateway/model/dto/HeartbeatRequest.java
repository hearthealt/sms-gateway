package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 心跳请求。字段名与长度约束的取舍同 {@link DeviceRegisterRequest}。
 *
 * <p>注意这里的 {@code deviceId} **不作数** —— 设备身份一律以拦截器认证出的为准
 * （见 {@code DeviceController#heartbeat}），请求体里带什么都只被忽略。
 * 字段保留是为了兼容既有客户端，不是身份来源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @NotBlank(message = "deviceId cannot be empty")
    @Size(max = 128, message = "deviceId 超长（上限 128）")
    private String deviceId;

    @Size(max = 255, message = "deviceName 超长（上限 255）")
    private String deviceName;

    @JsonAlias("phoneNumber")
    @Size(max = 32, message = "phone 超长（上限 32）")
    private String phone;

    /**
     * 以下遥测字段 Android 端一直在上报，此前 DTO 未接收导致被静默丢弃。
     */
    private Integer battery;

    @Size(max = 20, message = "network 超长（上限 20）")
    private String network;

    private Boolean charging;

    private Integer pendingCount;
}
