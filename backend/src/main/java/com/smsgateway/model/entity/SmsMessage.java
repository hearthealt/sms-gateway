package com.smsgateway.model.entity;

import com.smsgateway.model.enums.SmsStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sms_message")
public class SmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "local_message_id", nullable = false, length = 128)
    private String localMessageId;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "sender", nullable = false, length = 100)
    private String sender;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "code", length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SmsStatus status = SmsStatus.RECEIVED;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "receive_time", nullable = false)
    private LocalDateTime receiveTime;

    /**
     * 这段内容又收到过几次。
     *
     * <p><b>重复到达不新插行</b>：撞上 {@code uk_device_source_hash} 之后是在这一行上
     * 加一（见 {@code SmsService} 的去重分支），所以「重复」不是一种 status，
     * 而是这一行的一个属性 —— 它的 status 仍然是 RECEIVED 或 IGNORED。
     * 曾经这里写着「重复到达本身也各存一行（status=DUPLICATE）」，那是错的：
     * 唯一索引压根不允许同一台设备存两条同 hash 的行。
     *
     * <p>这个计数是为了让列表上一眼看到「重复 3 次 · 最后 15:20」—— 否则要按
     * source_hash 去 count 一遍，列表每行都查一次。管理端的「采集」列与展开行
     * 就是读它（见 SmsList.vue）；{@code SmsStatus.DUPLICATE} 只是回给设备端的
     * 上报响应，不会落到这一列上。
     */
    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}