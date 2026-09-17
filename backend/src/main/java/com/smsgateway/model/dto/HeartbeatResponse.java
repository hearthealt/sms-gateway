package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 心跳响应体。
 *
 * 设备状态走「正常响应里带状态」而不是错误码：这样被禁用的设备仍能继续心跳
 * （管理端因此看得到 last_heartbeat_at 仍在更新，能判断禁用是否真的送达了手机），
 * 设备也能从一条 200 里学到自己的状态。短信上报接口才用 403 拒绝。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatResponse {

    /** ACTIVE / DISABLED */
    private String status;
}
