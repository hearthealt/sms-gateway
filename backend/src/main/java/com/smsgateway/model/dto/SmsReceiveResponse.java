package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsReceiveResponse {

    private Long messageId;
    private boolean duplicate;
    private String status;
    private String smsCode;
}