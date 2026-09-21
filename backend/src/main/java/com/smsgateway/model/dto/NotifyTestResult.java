package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个转发渠道的测试结果，设备端「一键测转发链路」用。
 *
 * 带上渠道名而不是只回一个布尔：设备持有者要的是「码到底送到哪几个地方了」——
 * 「运维群 ✓ / 我的微信 ✗」才是能照着处置的东西，一句「测试失败」不是。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyTestResult {

    private Long channelId;

    private String channelName;

    private boolean ok;

    /** 成功时为空；失败时是给人看的原因（网络错误或对端返回的错误摘要）。 */
    private String message;
}
