package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对外接口（`POST /api/sms/send`）的返回。
 *
 * <p>**刻意不复用管理端的视图对象**，与 {@code ClientSmsView} 是同一个取舍：
 * {@code deviceId} / {@code createdBy} / {@code segments} 都是内部信息，
 * 调用方拿它们没用，而一旦出现在对外契约里就再也不能删。
 *
 * <p>{@code content} 也不返回：调用方刚把那段文字发给服务端，回显一遍只会让人以为
 * 服务端改写过它。真正该知道的是「发出去了没有」，那是 {@code status}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientOutboundView {

    private Long id;

    private String phone;

    /** PENDING / DISPATCHED / SENT / FAILED / CANCELLED / UNKNOWN，见 SmsOutboundStatus。 */
    private String status;

    private LocalDateTime createTime;

    /** 设备回报「已发出」的时刻。 */
    private LocalDateTime sentAt;

    /** 失败原因（受控文案）。 */
    private String errorReason;
}
