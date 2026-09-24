package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回执的处理结果。
 *
 * <p>只回一个数字：**属于本设备、且本次真的被改动的行数**。
 *
 * <p>为什么不是「收到的条数」：设备重复回执（回执丢了之后的重发）是常态，
 * 那些 id 早已转成终态、不该再被改。返回「改动了几行」让设备端能区分
 * 「服务端认了」与「服务端根本没认这条」（例如那条指令属于别的设备 ——
 * 那种情况一个数字都不会变）。设备据此决定要不要继续重发。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandAckResponse {

    private int accepted;
}
