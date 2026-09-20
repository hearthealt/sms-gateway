package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 设备接入口令：管理后台生成，随「快速连接」二维码下发。
 *
 * <p>新设备**首次注册**时必须携带它。挡的是这条路径：注册接口必须免鉴权（设备得先能
 * 注册才拿得到令牌），在那之前没有任何东西能区分「自己人」和「碰巧知道服务器地址的人」。
 *
 * <p>它是**单行表**，id 恒为 1（见 {@link #SINGLETON_ID}）。现场用法是「一张码贴在那里，
 * 谁来了扫一下」，全局一个口令 + 一键轮换最贴合，比每设备一张的管理成本低得多。
 *
 * <p>{@code token} 存的是**明文**，取舍同 {@link ApiKey}：管理后台要把它显示进二维码，
 * 不可回读的哈希做不到这一点。库被读走等同于口令泄露。
 *
 * <p>与 {@code SmsDevice.enrollSecretHash} 是两回事，不要混淆：那个是**设备身份**
 * （证明「我是这台设备」），这个是**服务器准入凭证**（证明「我被允许接入本服务器」）。
 * 前者只在设备已存在时校验，后者只在设备不存在时校验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_enroll_token")
public class DeviceEnrollToken {

    /**
     * 单行表的固定主键。
     *
     * <p>刻意不用自增：自增会让「不小心插进第二行」变成可能，而这张表一旦有两行，
     * {@code findById} 之外的写法（比如 findAll 取第一条）就会在两张口令之间摇摆 ——
     * 那是一种只在特定部署里才复现的故障。
     */
    public static final Long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "token", nullable = false, length = 64)
    private String token;

    /** false = 关闭准入校验，注册接口退回开放。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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
