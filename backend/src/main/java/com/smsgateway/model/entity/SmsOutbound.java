package com.smsgateway.model.entity;

import com.smsgateway.model.enums.SmsOutboundStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 一条外发短信：服务端下发、由某台设备发出去。表结构说明见 {@code schema.sql} 第 12 节。
 *
 * <p>命名与 {@link SmsDevice} / {@link EventLog} 一致：{@code deviceId} 是**主键外键**，
 * {@code deviceCode} 才是业务标识。
 *
 * <p>{@code @DynamicUpdate}：下发是「查出来 → 改状态 → save()」的读-改-写，
 * 默认 UPDATE 会写回所有列 —— 管理员恰好在这一瞬撤销（PENDING→CANCELLED）时，
 * 这次下发会把它覆盖回 DISPATCHED，于是**一条已经撤销的短信还是发了出去**。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sms_outbound")
@DynamicUpdate
public class SmsOutbound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_code", nullable = false, length = 128)
    private String deviceCode;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    /** 正文。**存库**（与 sms_message 相反）：这一条本来就是我们自己写进去的，不存等于黑箱。 */
    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SmsOutboundStatus status = SmsOutboundStatus.PENDING;

    /** ADMIN / API。用 String 而不是枚举：将来加一种来源不必动表。 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    /** 幂等键：随心跳交给设备，回执时带回。同一把 key 只会发出一条短信。 */
    @Column(name = "outbound_key", nullable = false, length = 64)
    private String outboundKey;

    @Column(name = "segments")
    private Integer segments;

    /** 指定卡槽；null = 设备自己选一张。 */
    @Column(name = "sim_slot")
    private Integer simSlot;

    /** 失败原因，受控文案。 */
    @Column(name = "error_reason", length = 255)
    private String errorReason;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
