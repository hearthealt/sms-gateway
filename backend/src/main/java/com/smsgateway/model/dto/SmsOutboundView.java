package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端的外发短信视图。
 *
 * <p>与 {@link DeviceCommandView} 同一个做法：中文名（状态标签）由服务端给，
 * 前端不硬编码 —— 加一个状态忘了补文案会编译不过（枚举构造器要求给 label）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsOutboundView {

    private Long id;

    /** 设备业务标识。 */
    private String deviceId;

    private String deviceName;

    private String phone;

    /** 正文。管理端要看得到自己发出去的是什么 —— 这一条不像收进来的短信那样含验证码。 */
    private String content;

    private String status;

    private String statusLabel;

    /** ADMIN / API。 */
    private String source;

    /** 管理员账号或 API Key 名称。 */
    private String createdBy;

    /** 分段数（计费条数）。设备回执之前为空。 */
    private Integer segments;

    private String errorReason;

    private LocalDateTime dispatchedAt;

    private LocalDateTime sentAt;

    private LocalDateTime deliveredAt;

    private LocalDateTime createTime;
}
