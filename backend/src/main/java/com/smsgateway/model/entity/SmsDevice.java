package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sms_device")
public class SmsDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 128)
    private String deviceId;

    @Column(name = "device_token", nullable = false, length = 256)
    private String deviceToken;

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