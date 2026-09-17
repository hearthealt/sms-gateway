package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsReceiveRequest {

    @NotBlank(message = "deviceId cannot be empty")
    private String deviceId;

    @NotBlank(message = "localMessageId cannot be empty")
    private String localMessageId;

    /**
     * 本机号码。允许为空：多数 Android 设备读不到自己的号码（注册接口的
     * phoneNumber 同样允许为 null），而短信本身是有效的，不该因为号码读不到就拒收。
     * SmsService 会把 null 归一成空串再入库，因为该列是 NOT NULL。
     */
    private String phone;

    @NotBlank(message = "sender cannot be empty")
    private String sender;

    @NotBlank(message = "content cannot be empty")
    private String content;

    private String code;

    private Long receiveTime;
}