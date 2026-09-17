package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 外部调用方密钥，由管理后台签发。
 *
 * <p>密钥是**明文存储**的：管理后台列表默认打码，但点「显示」要能回看完整值、
 * 点「复制」要能取到完整值，所以库里必须留明文。这意味着数据库被读走等同于密钥泄露 ——
 * 这是内部系统换取「管理员忘了密钥不用重新签发」的取舍。
 * 若日后要改成只存哈希，管理后台得改成「签发时仅显示一次」，
 * 且 {@code ApiKeyView.apiKey} 要换成掩码 + 单独的一次性下发接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用途备注，便于识别是哪个调用方。 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "api_key", nullable = false, unique = true, length = 64)
    private String apiKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 过期时间，null 表示不过期。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 最后一次通过鉴权的时间，每缓存周期最多刷新一次。 */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

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

    /** 是否已过期。expiresAt 为 null 表示长期有效。 */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
