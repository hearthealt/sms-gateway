package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sms_device")
// 心跳走的是「查出实体 → 改几个字段 → save()」的读-改-写，默认的 UPDATE 会写回**所有列**，
// 包括它读到快照时的 status。管理端若恰好在这一瞬把设备改成 DISABLED，这次心跳就会把它
// 覆盖回 ACTIVE —— 管理员看到「已禁用」，设备却仍能继续上报（拦截器只在 status 为
// DISABLED 时拒绝 /api/sms/receive）。批量禁用一批设备时命中概率明显上升。
//
// @DynamicUpdate 让 Hibernate 只把**变脏的列**写进 SET，没被本事务改过的 status 就不会
// 出现在 UPDATE 里，竞态窗口随之消失。比改成定向 @Modifying UPDATE 小得多，也不用
// 为那几个可选字段（deviceName / battery / network…）逐个写 coalesce。
@DynamicUpdate
public class SmsDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 128)
    private String deviceId;

    @Column(name = "device_token", nullable = false, length = 256)
    private String deviceToken;

    /**
     * 重注册密钥的 SHA-256（十六进制）。设备首次注册时自带一个随机密钥，服务端只存哈希。
     *
     * <p>它挡的是这个洞：注册接口必须免鉴权（设备得先能注册才拿得到令牌），而对**已存在**
     * 的 deviceId，原先的实现在重注册分支里把令牌原样返还 —— 于是「知道设备号」就等于
     * 「能冒充这台设备」。而设备号在管理后台列表、设备端界面、任何截图里都可见。
     *
     * <p>为 null 表示该设备**尚未启用重注册校验**（本次变更之前注册的老设备）：
     * 它们没有密钥可比对，因此不会再被返还令牌，需要管理员签发一次恢复码来启用。
     */
    @Column(name = "enroll_secret_hash", length = 64)
    private String enrollSecretHash;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "platform", length = 50)
    private String platform;

    @Column(name = "app_version", length = 50)
    private String appVersion;

    @Column(name = "battery")
    private Integer battery;

    @Column(name = "network", length = 20)
    private String network;

    @Column(name = "charging")
    private Boolean charging;

    @Column(name = "pending_count")
    private Integer pendingCount;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /**
     * 设备主动报告「网关已停止」的时刻。
     *
     * <p>与 lastHeartbeatAt 一起判定在线：心跳在 90 秒内 **且** 晚于这个时刻才算在线。
     * 没有它，用户点了「停止网关」之后管理后台还要再显示 90 秒在线。
     *
     * <p>可为空 = 从没报告过（老版本 App、或进程被杀没来得及报）。
     */
    @Column(name = "reported_offline_at")
    private LocalDateTime reportedOfflineAt;

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