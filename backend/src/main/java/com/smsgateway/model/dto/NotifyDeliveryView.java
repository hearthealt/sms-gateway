package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 投递记录的管理端视图。
 *
 * <p><b>不返回渲染后的消息正文</b>：它含验证码明文，落库都没有存，这里当然也不给。
 * 排查需要看内容时，{@code contentPreview} 是**打过码**的短信原文，
 * 另外还有 {@code smsMessageId} 可以关联回短信列表看完整那条（那是管理员的既有权限）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyDeliveryView {

    private Long id;
    private Long smsMessageId;
    private Long channelId;
    /** 渠道名。列表上只显示一个 id 的话，管理员得自己去对。 */
    private String channelName;

    /**
     * 所属渠道是否启用。
     *
     * <p>**它是「这条记录还会不会发出去」的关键**：渠道被停用后，那条记录会一直停在
     * 待投递、`nextRetryAt` 也停在过去的时间 —— 只看状态会读成「马上要重试了」，
     * 而实际上它不会再发。前端据此把状态显示成「已暂停」、把下次重试显示成「-」。
     */
    private boolean channelEnabled;

    private String status;
    private int attempts;
    private LocalDateTime nextRetryAt;
    private Integer responseCode;
    /** 已脱敏的错误摘要，不会出现完整 webhook 地址。 */
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;

    // ---- 关联短信的摘要（打码后） ----
    private String sender;
    private String phone;
    private String contentPreview;
}
