package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsWaitResponse {

    private String smsCode;
    private String sender;
    private String content;
    private String phone;
    private Long receiveTime;
}