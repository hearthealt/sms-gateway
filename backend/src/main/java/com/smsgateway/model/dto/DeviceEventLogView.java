package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 设备端读取**自己**在服务端的运行日志。
 *
 * <p>给「一键导出诊断包」用：最常见的现场是「设备说传上去了、服务端说没收到」，
 * 而答案就在服务端事件里（存下 / 重复 / 被规则忽略 / 被拒）。只有设备本地那一半，
 * 报告读过之后仍然回答不了那个问题。
 *
 * <p>同 {@code GET /api/device/sms}：设备读自己的数据，设备身份取自令牌，
 * **不接受请求里的 deviceId**。
 *
 * <p>{@code phone} **在服务端就打码**。诊断包会离开设备、进到聊天工具里，
 * 多一道防线不多余 —— 而这里打码不损失信息（管理员在运行日志页看的是完整值）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEventLogView {

    private String type;

    /** 中文名，服务端给（枚举上带着）。 */
    private String label;

    private String level;

    private String reason;

    /** 短信发送方。号码类元数据，与运行日志页一致，不打码（它不是本机号码）。 */
    private String sender;

    /** 接收号码，**已打码**。 */
    private String phone;

    private LocalDateTime at;
}
