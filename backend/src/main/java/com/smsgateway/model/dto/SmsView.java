package com.smsgateway.model.dto;

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

    /** 这段内容后来又收到过几次。0 = 只收到过一次。 */
    private int duplicateCount;

    /**
     * 最后一次收到这条内容的时刻。
     *
     * 与 receiveTime 不同：receiveTime 是**首次**收到的时间（由设备上报），
     * 重复到达会把这个顶上去 —— 列表上「重复 3 次 · 最后 15:20:11」里的那个时间就是它。
     */
    private LocalDateTime updatedAt;
}
