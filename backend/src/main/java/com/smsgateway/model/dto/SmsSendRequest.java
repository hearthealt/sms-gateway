package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 请求发一条短信。管理端与外部的 `POST /api/sms/send` 共用这个形状。
 *
 * <p><b>{@code phone} 是收信方，{@code deviceId} 是「用哪台设备发」</b> —— 别混。
 * 设备是靠它**自己的**号码登记的（{@code SmsDevice.phoneNumber}），而那个号
 * 绝大多数时候与收信方不是同一个，所以服务端**不会**拿 {@code phone} 去反查设备。
 *
 * <p>{@code deviceId} 可以省略，但仅在服务器上恰好只有一台设备时才自动采用；
 * 有多台时明确要求指定 —— 发错手机的代价是「用别人的号发了一条短信」，
 * 而调用方从返回里看不出选错了。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendRequest {

    /** **收信方**号码。允许带 +86、空格、横线，服务端归一化后再用。 */
    @NotBlank(message = "phone 不能为空")
    @Size(max = 32, message = "phone 超长（上限 32）")
    private String phone;

    /**
     * 正文。
     *
     * <p>500 字的上限由服务端在 Service 里按**字符数**校验（不是 `@Size` 的字节数）——
     * 这条短信是要计费的，一条几千字的短信会被拆成几十条，而调用方多半不知道自己在干什么。
     */
    @NotBlank(message = "content 不能为空")
    private String content;

    /**
     * 用哪台设备发（业务标识）。
     *
     * <p>留空时：服务器上只有一台设备就用它；有多台则报错要求显式指定（见类注释）。
     */
    @Size(max = 128, message = "deviceId 超长")
    private String deviceId;

    /** 指定卡槽；留空 = 设备自己选一张。 */
    private Integer simSlot;
}
