package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 外部调用方看到的短信。
 *
 * <p>刻意不复用管理后台的 {@link SmsView} —— 那个带 deviceId / deviceName / status / isRead，
 * 属于内部信息，不该出现在对外契约里。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientSmsView {

    private Long id;
    private String phone;
    private String sender;
    private String content;
    private String code;
    private LocalDateTime receiveTime;
}
