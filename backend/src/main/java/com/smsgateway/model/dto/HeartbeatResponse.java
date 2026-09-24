package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * 下发给这台设备的远程指令。**恒定出现**，没有时是空列表。
     *
     * <p>不做「空则省略」：省略会让设备端多一种要处理的形态（字段可能不存在），
     * 而一个空列表本来就没有歧义 —— 少一个分支就少一处会写错的地方。
     *
     * <p>指令搭心跳下发而不是新开长轮询，理由见 {@code DeviceCommandService} 的类注释。
     */
    private List<DeviceCommandPayload> commands;

    /**
     * 交给这台设备去发的外发短信。**恒定出现**，没有时是空列表。
     *
     * <p>与指令同一个下行通道（心跳），但**语义完全不同**：指令重发的代价是「设备可能
     * 多做一次」，而短信重发的代价是真的又发一条出去、又计费一次 —— 所以外发短信
     * **只下发一次**，下发之后就不再出现在这里（见 {@code SmsOutboundService}）。
     *
     * <p>探测心跳（{@code commandProbe=true}）**不携带**它：用户停掉网关的意图就是
     * 「这台机器冻结住」，那时让它发短信是违背这个意图的。
     */
    private List<SmsOutboundPayload> outbound;
}
