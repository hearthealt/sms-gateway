package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理后台的运行事件视图。字段名对齐前端 types/index.ts 的 EventLogItem。
 *
 * <p><b>这里没有 content，也没有 code</b> —— 不是忘了加，是这张表本来就不存它们
 * （见 {@code EventLog} 的类注释）。DTO 层面也不给，省得将来有人顺手在前端加上一列。
 * 要看内容按 {@link #smsMessageId} 去短信记录页查。
 *
 * <p>{@code deviceId} 是**业务设备标识**（如 android-1234），不是数据库主键 ——
 * 与 {@link SmsView} 同一个约定。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventLogView {

    private Long id;

    /** 枚举名，供前端做筛选与逻辑判断。 */
    private String eventType;

    /** 中文标签，直接渲染。来自后端枚举，前端不硬编码。 */
    private String typeLabel;

    /** INFO / WARN / ERROR。 */
    private String level;

    private String deviceId;
    private String deviceName;

    private String sender;
    private String phone;

    /** 判定结果 / 原因码 / HTTP 状态。 */
    private String reason;

    /**
     * 关联的短信主键；可能已经被保留策略清掉，前端只能当作「可跳转的线索」，
     * 不能假定它一定查得到。
     */
    private Long smsMessageId;

    private LocalDateTime createdAt;
}
