package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 下发给设备的指令视图。
 *
 * <p><b>刻意只有四个字段。</b>{@code status} / {@code attempts} / {@code issuedBy}
 * 是管理端的运维信息，设备一个都不需要 —— 每多一个字段就是多一处信息暴露面，
 * 而这条通道是服务端主动推向设备方向的，越窄越好。
 *
 * <p>{@code expiresAt} 必须带上：设备要自己判一次过期，不等服务端告知。
 * 两个时钟各自得出结论，比让设备去「报告这条已经过期了」少一个状态
 * （见 {@code CommandExecutor} 里那段说明）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandPayload {

    private Long id;

    /** 见 DeviceCommandType 枚举。设备按名字分派；不认识就回执 REJECTED。 */
    private String type;

    /** 指令参数，多数类型为 null。 */
    private String argument;

    private LocalDateTime expiresAt;
}
