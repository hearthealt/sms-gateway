package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 按天统计的单日数据点，用于仪表盘趋势图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyCount {

    /** 日期，格式 yyyy-MM-dd。 */
    private String day;

    /** 当日短信总数。 */
    private long value;

    /** 当日识别出验证码的条数。 */
    private long codes;
}
