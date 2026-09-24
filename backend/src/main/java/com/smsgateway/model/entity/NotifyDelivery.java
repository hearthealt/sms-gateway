package com.smsgateway.model.entity;

import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.NotifyDeliverySource;
import com.smsgateway.model.enums.NotifyDeliveryStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条投递记录，兼作事务性发件箱（outbox）。
 *
 * <p>这一行的 INSERT 与 {@link SmsMessage} 的 INSERT 在**同一个事务**里 ——
 * 事务提交则投递任务必然存在，回滚则两者都不存在，不存在「短信存了但没转发」的
 * 中间态。这是把转发做在服务端的根本理由：放在设备上就没有事务可依附，
 * 设备上报成功、转发失败时服务端根本看不见。
 *
 * <p><b>刻意不存渲染后的短信正文</b>：正文含验证码明文，落库等于把验证码写了两遍、
 * 且第二遍没有 TTL。排查用 {@code lastError} + {@code responseCode} 够；
 * 要看正文按 {@code smsMessageId} 关联回 sms_message（那里本来就有）。
 *
 * <p><b>告警（{@link NotifyDeliverySource#ALERT}）也走这张表</b>，理由与代价见
 * {@link NotifyDeliverySource}。告警的正文摘要**是落库的**（{@code alertSummary}），
 * 这一条与短信刻意相反：短信不落是因为正文含验证码明文，而重新渲染告警要回头解析
 * {@code subjectKey} 对应的设备行 —— 那一行可能已经被删了，于是控制台上会出现
 * 一行没有内容的告警。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notify_delivery")
public class NotifyDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联短信；告警投递为 null。
     *
     * <p>列上的唯一索引 {@code uk_sms_channel} 原样保留 —— InnoDB 的唯一索引允许
     * 出现多个 NULL，所以告警行不受它约束，而短信行的幂等性一个字都没变。
     */
    @Column(name = "sms_message_id")
    private Long smsMessageId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    /** 载荷来源。老行默认 SMS，因此本次变更不需要数据迁移。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private NotifyDeliverySource sourceType = NotifyDeliverySource.SMS;

    /** 仅 ALERT 行有值。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", length = 32)
    private AlertType alertType;

    /** 告警主体，如 {@code device:42} / {@code channel:7}。去重与「是哪台设备」都靠它。 */
    @Column(name = "subject_key", length = 160)
    private String subjectKey;

    /** 告警正文摘要，仅 ALERT 行有值。 */
    @Column(name = "alert_summary", length = 255)
    private String alertSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotifyDeliveryStatus status = NotifyDeliveryStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /**
     * 调度用：到点才捞。
     *
     * <p>限流取不到令牌时**也写这里**（往后推），而不是失败重试 ——
     * 限流是预期内的，不该消耗重试次数 —— 否则「连续失败 N 次自动停用渠道」
     * 会在一个繁忙的下午把一条都没发失败过的渠道停掉。
     */
    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "response_code")
    private Integer responseCode;

    /** 脱敏后的错误摘要，写之前必须过 {@code NotifyRedactor}。 */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 新建一条待投递记录。立即到期，所以调度器下一轮就会捞到。 */
    public static NotifyDelivery pending(Long smsMessageId, Long channelId) {
        NotifyDelivery delivery = new NotifyDelivery();
        delivery.setSmsMessageId(smsMessageId);
        delivery.setChannelId(channelId);
        delivery.setSourceType(NotifyDeliverySource.SMS);
        delivery.setStatus(NotifyDeliveryStatus.PENDING);
        delivery.setNextRetryAt(LocalDateTime.now());
        return delivery;
    }

    /**
     * 新建一条**告警**投递记录。{@code smsMessageId} 留 null。
     *
     * <p>与短信那条唯一的区别是载荷来源：{@code sendOne} 会按它决定去
     * {@code sms_message} 取正文，还是用这里带上的摘要现拼。
     */
    public static NotifyDelivery alert(AlertType alertType, String subjectKey, String summary, Long channelId) {
        NotifyDelivery delivery = new NotifyDelivery();
        delivery.setChannelId(channelId);
        delivery.setSourceType(NotifyDeliverySource.ALERT);
        delivery.setAlertType(alertType);
        delivery.setSubjectKey(subjectKey);
        delivery.setAlertSummary(summary);
        delivery.setStatus(NotifyDeliveryStatus.PENDING);
        delivery.setNextRetryAt(LocalDateTime.now());
        return delivery;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextRetryAt == null) {
            nextRetryAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
