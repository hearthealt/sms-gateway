package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegisterResponse {

    private String deviceToken;
    private String deviceId;

    /**
     * 设备当前状态：ACTIVE / DISABLED。
     *
     * 必须是实体上的 status（SmsDevice.status），**不能**取 DeviceView.status ——
     * 后者是 online/offline/DISABLED 的展示值，会把一台健康的设备报成 offline，
     * 客户端据此判断会误以为设备被禁用。
     *
     * 刻意不给默认值：三处构造点必须全部显式传参，否则漏掉的那处会静默返回 null，
     * 客户端把「未知」当成「可用」。
     */
    private String status;
}
