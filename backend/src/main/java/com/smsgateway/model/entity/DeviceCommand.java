package com.smsgateway.model.entity;

import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.DeviceCommandType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 一条下发给设备的远程指令。表结构说明见 {@code schema.sql} 第 10 节。
 *
 * <p><b>{@code deviceId} 是主键外键，{@code deviceCode} 才是业务标识</b> ——
 * 与 {@link EventLog} 同一套命名。冗余存业务标识是为了设备行被删掉之后，
 * 列表上仍然认得出这条指令原本是给谁的（同 event_log.device_code 的理由）。
 *
 * <p>{@code @DynamicUpdate} 与 {@link SmsDevice} 同源：下发是「查出实体 → 改几个字段
 * → save()」的读-改-写，默认 UPDATE 会写回**所有列**，包括读快照时的 status。
 * 管理员恰好在这一瞬撤销了这条指令，这次下发就会把它从 CANCELLED 覆盖回 SENT，
 * 而设备随后照样执行 —— 撤销失效，且界面上看不出发生过什么。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_command")
@DynamicUpdate
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 sms_device.id。 */
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 设备业务标识冗余，设备行不在了也认得出。 */
    @Column(name = "device_code", nullable = false, length = 128)
    private String deviceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 32)
    private DeviceCommandType commandType;

    /** 指令参数，目前只有 {@link DeviceCommandType#SET_PHONE} 用到（号码）。 */
    @Column(name = "argument", length = 255)
    private String argument;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeviceCommandStatus status = DeviceCommandStatus.PENDING;

    /** 已下发次数。设备重复收到靠 id 去重，这个计数只用于「放弃」判定。 */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 到点才下发。否则一条未回执的指令会在每个心跳上都重发一次。 */
    @Column(name = "next_deliver_at", nullable = false)
    private LocalDateTime nextDeliverAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** 签发时按类型定死并落库，不在查询时算 —— 让当时的策略可审计。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    /** 设备回报的一句话。受控文案，服务端截断到 255。 */
    @Column(name = "result_detail", length = 255)
    private String resultDetail;

    /** 签发这条指令的管理员账号。 */
    @Column(name = "issued_by", length = 64)
    private String issuedBy;

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
