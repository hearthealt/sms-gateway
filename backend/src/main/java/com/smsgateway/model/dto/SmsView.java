package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理后台短信视图。字段名对齐前端 types/index.ts 的 SmsRecord。
 * 注意 deviceId 为业务设备标识（如 android-1234），不是数据库主键。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsView {

    private Long id;
    private String deviceId;
    private String deviceName;
    private String phone;
    private String sender;
    private String content;
    private String code;
    private String status;
    private LocalDateTime receiveTime;

    /**
     * 显式指定 JSON 名。Lombok 为 boolean isRead 生成的是 isRead()，
     * Jackson 默认会推导出属性名 "read"，与前端约定的 isRead 不符。
     */
    @JsonProperty("isRead")
    private boolean isRead;
}
