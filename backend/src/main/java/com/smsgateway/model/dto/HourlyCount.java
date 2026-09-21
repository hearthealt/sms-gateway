package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 按小时统计的单点数据，用于设备端主页的「今日分布」。
 *
 * 与 {@link DailyCount} 分开是因为字段语义不同（小时 0-23 vs 日期字符串），
 * 合成一个类只会让两边都要写「这个字段什么时候有意义」的注释。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HourlyCount {

    /** 小时，0-23。 */
    private int hour;

    /** 该小时收到的短信数（不含被规则忽略的）。 */
    private long value;

    /** 其中识别出验证码的条数。 */
    private long codes;
}
