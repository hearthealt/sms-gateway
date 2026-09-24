package com.smsgateway.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 心跳请求。字段名与长度约束的取舍同 {@link DeviceRegisterRequest}。
 *
 * <p>注意这里的 {@code deviceId} **不作数** —— 设备身份一律以拦截器认证出的为准
 * （见 {@code DeviceController#heartbeat}），请求体里带什么都只被忽略。
 * 字段保留是为了兼容既有客户端，不是身份来源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @NotBlank(message = "deviceId cannot be empty")
    @Size(max = 128, message = "deviceId 超长（上限 128）")
    private String deviceId;

    @Size(max = 255, message = "deviceName 超长（上限 255）")
    private String deviceName;

    @JsonAlias("phoneNumber")
    @Size(max = 32, message = "phone 超长（上限 32）")
    private String phone;

    /**
     * 以下遥测字段 Android 端一直在上报，此前 DTO 未接收导致被静默丢弃。
     */
    private Integer battery;

    @Size(max = 20, message = "network 超长（上限 20）")
    private String network;

    private Boolean charging;

    private Integer pendingCount;

    /**
     * 这是一次「网关已停止」时的低频探测心跳，不是常规心跳。
     *
     * <p>存在的理由是一个死结：远程指令搭心跳下发，而用户停了网关之后**没有人发心跳** ——
     * 于是「启动网关」这条最需要在「已停」状态下送达的指令，恰好送不到。
     * 设备用一个 15 分钟的 WorkManager 周期任务发这种心跳（见 CommandProbeWorker），
     * 服务端在它上面**只下发 START_GATEWAY**。
     *
     * <p><b>为 true 时服务端必须完全跳过在线状态更新</b>：不写 last_heartbeat_at、
     * 不写 Redis 在线键、不记 DEVICE_ONLINE、不广播 devices。这一条是硬性的 ——
     * 不这么做，探测心跳会让「用户点了停止之后后台立刻变灰」这个已有特性失效，
     * 后台会隔 15 分钟闪一下在线。理由详见 {@code DeviceService.heartbeat}。
     *
     * <p>为 null（老版本 App、或常规心跳）时按常规处理，行为与本次变更之前完全一致。
     */
    private Boolean commandProbe;

    /**
     * 设备对外发短信的执行结果。
     *
     * <p><b>随心跳带上，不单开一个接口</b>：这条短信已经离开手机了，回执晚几秒完全
     * 无所谓；而多一个接口就多一处鉴权、限流、错误处理要维护。设备本来每 30 秒
     * 就在发心跳。
     *
     * <p>设备会把**最近若干条**未确认的结果每次都带上（它不知道服务端收没收到），
     * 所以服务端必须幂等（见 {@code SmsOutboundService.applyResults}）。
     *
     * <p>探测心跳里**也可以**带：网关虽然停了，但停之前发出去那些短信的结果
     * 还是该报上来。
     *
     * <p><b>刻意不标 {@code @Valid}</b>：一个格式不对的回执不该让整条心跳 400 ——
     * 那会让设备连上报、指令回执一起卡住，一个小毛病换来一个大故障。
     * 服务端逐条跳过不认识的即可。
     */
    private List<OutboundResult> outboundResults;
}
