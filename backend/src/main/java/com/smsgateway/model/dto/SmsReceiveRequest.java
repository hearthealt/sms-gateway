package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 设备上报的短信。
 *
 * <p>每个字段的长度上限都对齐建表脚本里的列宽。不加上限的后果不只是「500 而不是 400」：
 * 设备端把 500 归为**可重试**，于是一条超长的 sender（国外长号或异常 ROM 会出现）
 * 会让这条短信永远重试下去、一直烧电，而现场完全不知道是长度问题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsReceiveRequest {

    @NotBlank(message = "deviceId cannot be empty")
    @Size(max = 128, message = "deviceId 超长（上限 128）")
    private String deviceId;

    @NotBlank(message = "localMessageId cannot be empty")
    @Size(max = 128, message = "localMessageId 超长（上限 128）")
    private String localMessageId;

    /**
     * 本机号码。允许为空：多数 Android 设备读不到自己的号码（注册接口的
     * phoneNumber 同样允许为 null），而短信本身是有效的，不该因为号码读不到就拒收。
     * SmsService 会把 null 归一成空串再入库，因为该列是 NOT NULL。
     */
    @Size(max = 32, message = "phone 超长（上限 32）")
    private String phone;

    @NotBlank(message = "sender cannot be empty")
    @Size(max = 100, message = "sender 超长（上限 100）")
    private String sender;

    @NotBlank(message = "content cannot be empty")
    // 列类型是 TEXT（65535 字节）。这里按字符计上限 10000，utf8mb4 之下约 40000 字节，
    // 留足余量 —— 目的是挡住异常输入，不是贴着列的物理上限卡。
    @Size(max = 10000, message = "content 超长（上限 10000 字符）")
    private String content;

    @Size(max = 20, message = "code 超长（上限 20）")
    private String code;

    private Long receiveTime;
}