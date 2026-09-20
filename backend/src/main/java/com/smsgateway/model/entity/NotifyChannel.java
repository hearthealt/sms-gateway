package com.smsgateway.model.entity;

import com.smsgateway.model.enums.NotifyChannelType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 转发渠道：短信到达后推到哪里去（企微群、通用 webhook……）。
 *
 * <p>{@code configCipher} 是**密文**，与 {@link ApiKey} 的明文存储刻意相反。
 * api_key 那里必须明文，因为管理端要支持「点显示看完整值」；而 webhook 地址创建时
 * 贴一次就够，之后只需要知道「配好了」，没有回显明文的场景 —— 能做加密就做。
 * 密钥来自 {@code app.notify.encrypt-key}，不跟着库走。
 *
 * <p>**没有消息模板，也不给验证码打码** —— 转发出去的就是短信原文。
 * 这意味着群里所有人都能用看到的验证码登录对应账号；这是配置渠道时就该知道的事
 * （谁在那个群里），管理端在建渠道时提示一次，不在运行时拦。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notify_channel")
public class NotifyChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotifyChannelType type;

    /** AES-GCM 密文，含随机 IV。解密后是该渠道类型的配置 JSON。 */
    @Column(name = "config_cipher", nullable = false, columnDefinition = "TEXT")
    private String configCipher;

    /** 每分钟上限，0 = 不限。默认值由各 ChannelSender 按官方限额的保守档给出。 */
    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin = 20;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    /**
     * **脱敏后**的错误摘要。
     *
     * <p>写之前必须过 {@code NotifyRedactor}：对端的原始报错里常常把整个请求 URL 回显
     * 出来，而 webhook URL 本身就是凭证 —— 存进这里等于把密钥写进了数据库，
     * 管理端每打开一次渠道列表就再显示一遍。
     */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

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

    /** 连续失败到阈值就该自动停用，见 NotifyDispatcher。 */
    public boolean exceededFailureThreshold(int threshold) {
        return consecutiveFailures >= threshold;
    }
}
