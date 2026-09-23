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

    /**
     * 发送方号码。**允许为空**：个别 PDU（部分厂商 ROM、某些 alphanumeric 发送方）
     * 解出的 originatingAddress 是 null，设备端只能送空串上来。
     *
     * <p>这里原先有 {@code @NotBlank}。后果不是「这条被拒」这么简单：设备端把 400 归为
     * 终态 {@code failed}，而本地查询只取 {@code pending} —— 于是这条短信**再也不会被重试**，
     * 用户还在等的那个验证码就此消失。而短信本身是完整的、正文里就有码，
     * 不该因为发件人读不到而整条丢掉（与 {@link #phone} 允许为空是同一个取舍）。
     *
     * <p>代价：转发频道名里 sender 段为空（{@code sms:channel:{phone}:}），
     * 按发送方订阅的调用方匹配不到 —— 那是降级，不是丢失。
     */
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