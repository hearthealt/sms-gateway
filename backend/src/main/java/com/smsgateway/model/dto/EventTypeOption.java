package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事件类型的一个选项，供管理端的下拉框。
 *
 * <p>与转发渠道的 {@code /channel/types} 同一个做法：**选项从后端取，前端不硬编码**
 * —— 否则加一个事件类型就要改两处，而漏掉的那处表现是下拉框里少一项，
 * 且后台明明有这种事件却筛不出来。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTypeOption {

    /** 枚举名，筛选时回传的值。 */
    private String value;

    /** 中文标签，直接显示。 */
    private String label;

    /** 这类事件默认的级别，前端可以据此给选项上色。 */
    private String level;
}
