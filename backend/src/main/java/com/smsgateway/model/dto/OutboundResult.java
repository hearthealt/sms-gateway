package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备对一条外发短信的回执，随下一次心跳带上。
 *
 * <p><b>走心跳而不是单独一个接口</b>：回执晚几秒完全无所谓（这条短信已经离开手机了），
 * 而多一个接口就多一处鉴权、限流、错误处理要维护。设备那边也不需要在发完之后
 * 立刻再开一次请求 —— 它本来每 30 秒就在发心跳。
 *
 * <p><b>这个类上刻意没有 Bean Validation 注解</b>（承载它的
 * {@code HeartbeatRequest.outboundResults} 上也没有 {@code @Valid}）。
 * 这是一个有意的取舍：一个格式不对的回执如果让**整条心跳 400**，设备会连带上报、
 * 指令回执、外发结果全部卡住 —— 一个小毛病换来一个大故障。服务端改为
 * 「不认识就跳过这一条」（见 {@code SmsOutboundService.applyResults}），
 * 代价只是那条短信停在「结果未知」，而那是这份状态机里已经存在的一格。
 *
 * <p>同理，{@code status} 是 String 而不是枚举：不认识的值只是被跳过，
 * 不会让整条心跳反序列化失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboundResult {

    /** 幂等键，来自心跳下发的 {@code SmsOutboundPayload.key}。 */
    private String key;

    /** SENT（已发出）/ DELIVERED（对方已收到）/ FAILED（发不出去）。 */
    private String status;

    /** 失败原因，受控文案（错误码或异常类名）。 */
    private String errorReason;

    /**
     * 这条长短信实际被拆成了几段 —— **直接等于计费条数**。
     *
     * <p>由设备回报而不是服务端按字符数估：只有设备知道它那张卡走的是 GSM-7 还是
     * UCS-2（中文 67 字/段、英文 153 字/段），估出来的数字与话单对不上。
     */
    private Integer segments;
}
