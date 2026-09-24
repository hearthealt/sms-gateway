package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条指令的回执。
 *
 * <p><b>{@code status} 是 String 而不是枚举，这是刻意的。</b>写成枚举时，
 * 设备发来一个不认识的值会让 Jackson 抛 {@code HttpMessageNotReadableException}，
 * 而 {@code GlobalExceptionHandler} 没有接它 —— 结果是设备拿到一个 **500**
 * 「服务器内部错误」，而真相只是「设备端的词表比服务端新」。
 * 服务端这边显式校验并回 400，才能让这条偏差指向正确的方向。
 *
 * <p>取值只有三个：{@code DONE} / {@code FAILED} / {@code REJECTED}。
 * {@code REJECTED} 是给**旧版本 App** 用的：它收到了一个自己不认识的新指令类型，
 * 于是明确回一句「我做不到」。没有这一档的话，那种指令会在设备连续不回执中
 * 慢慢耗到 EXPIRED，管理员看到的是「设备没反应」，而实际原因在客户端版本上。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandAckItem {

    @NotNull(message = "id 不能为空")
    private Long id;

    /** DONE / FAILED / REJECTED */
    @NotNull(message = "status 不能为空")
    @Size(max = 16, message = "status 超长")
    private String status;

    /**
     * 一句话说明。**受控文案**：设备侧只发固定短语，不拼异常 message。
     *
     * <p>它会落进一张长期留存的表，所以要当成设备可控文本来对待（服务端截断到 255）。
     */
    @Size(max = 255, message = "detail 超长（上限 255）")
    private String detail;
}
