package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备今日的短信统计。
 *
 * 由服务端计算并回传，而不是让设备统计本地库 —— 设备端的记录页展示的就是服务端数据，
 * 两者同源才不会出现「数字是 0、点进去却有内容」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSmsStats {

    /** 今日收到的短信数（含被规则忽略的，与记录页列出的条数一致）。 */
    private long todaySms;

    /** 今日解析出验证码的条数。 */
    private long todayCodes;
}
