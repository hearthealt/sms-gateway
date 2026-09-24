package com.smsgateway.model.entity;

import com.smsgateway.model.enums.AlertType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 故障告警规则：什么坏了就通知谁。
 *
 * <p>与 {@link NotifyRoute} **刻意不复用**，与它「不复用 {@link SmsCollectRule}」是
 * 同一个理由，只是这一层更直接：路由的匹配维度全是短信的（发送方 / 正文关键词 /
 * 接收号码），而告警的维度是「什么坏了 + 哪台设备」。把 {@code senderPattern} 临时
 * 当成「告警类型」用，就是让一列承载两种语义 —— 改一次告警要回头读一遍转发逻辑。
 *
 * <p>与路由一致的地方是刻意保留的：渠道走 {@code @ElementCollection} 关联表、
 * 设备限定用精确相等、{@code enabled} 单独一列。
 *
 * <p>{@code alertType} 为 null 表示**不限类型**，与路由里「空模式 = 不限制这一项」
 * 同一个写法。所以一条「全部告警发到运维群」的规则只填一个渠道就够了。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "alert_rule")
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** null = 不限类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", length = 32)
    private AlertType alertType;

    /**
     * 限定设备（业务标识），空 = 不限。
     *
     * <p>与 {@link NotifyRoute#getDeviceId()} 一样用**精确相等**而不是 matchType：
     * 设备号是 128 字符的标识符，用 LIKE 匹配它只会让人以为可以模糊查。
     */
    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 这条规则命中后要投递到哪些渠道。写法与 {@link NotifyRoute#getChannelIds()} 一致。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "alert_rule_channel",
            joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "channel_id", nullable = false)
    private Set<Long> channelIds = new HashSet<>();

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
