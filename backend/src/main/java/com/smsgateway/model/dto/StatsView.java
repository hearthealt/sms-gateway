package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 仪表盘 KPI 统计。字段名对齐前端 types/index.ts 的 Stats（totalDevices 为前端附加字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsView {

    private long onlineDevices;
    private long offlineDevices;
    private long totalDevices;
    private long todaySms;
    private long todayCodes;
}
