package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 设备端主页的两张小图：近 N 天 + 今日逐小时。
 *
 * 合成一个响应而不是两个端点：这两张图永远一起出现、刷新节奏也一样，
 * 分成两个接口就是每次刷新多一个往返 —— 而这台设备是 7×24 挂着的。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTrend {

    /** 近 N 天，按天升序，**缺的天已补 0**（图里要有空柱子，不能跳过去）。 */
    private List<DailyCount> daily;

    /** 今日 0-23 点，**24 个点一个不少，没有数据的小时补 0**。 */
    private List<HourlyCount> hourly;
}
