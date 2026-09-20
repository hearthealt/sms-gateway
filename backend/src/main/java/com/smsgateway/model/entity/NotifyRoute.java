package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 转发规则：哪些短信转发到哪些渠道。
 *
 * <p>与 {@link SmsCollectRule} 是两套东西，**刻意不复用**：
 * 采集规则决定「哪些短信进系统」，是数据入口策略；路由规则决定「进来的短信发给谁」，
 * 是通知策略。混在一张表里，改通知会牵动数据采集。
 *
 * <p>最关键的差异是**语义**：采集规则首条命中即定论（采不采集是个二值判定），
 * 而路由是**并集** —— 一条短信可以同时进「运维群」和「我的微信」，所以这里是
 * 遍历全部规则、把所有命中的渠道合起来，不短路。
 *
 * <p>匹配条件的写法与采集规则完全一致（{@code %}/{@code _}/正则），
 * 共用 {@code RuleMatcher}，避免第二套 LIKE→正则的实现出现分叉。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notify_route")
public class NotifyRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_name", nullable = false, length = 100)
    private String routeName;

    @Column(name = "sender_pattern", length = 255)
    private String senderPattern;

    @Column(name = "keyword_pattern", length = 255)
    private String keywordPattern;

    @Column(name = "match_type", nullable = false, length = 20)
    private String matchType = "LIKE";

    /** 限定设备，空 = 不限。 */
    @Column(name = "device_id", length = 128)
    private String deviceId;

    /** 限定接收号码，多卡场景用，空 = 不限。 */
    @Column(name = "phone_pattern", length = 64)
    private String phonePattern;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * 这条规则命中后要投递到哪些渠道。
     *
     * <p>用 {@code @ElementCollection} 而不是自己写一个只有 (route_id, channel_id)
     * 两个列的实体：那张关联表没有第三列、也不需要单独查询，为它建实体 + Repository
     * 只会多出两份要维护的代码，而真实的写法就是「一组 id」。增删由 JPA 跟着这条规则
     * 一起做，不会出现改了规则忘了同步关联表的半截状态。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "notify_route_channel",
            joinColumns = @JoinColumn(name = "route_id"))
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
