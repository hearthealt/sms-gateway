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
@Table(name = "sms_collect_rule")
public class SmsCollectRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(name = "sender_pattern", nullable = false, length = 255)
    private String senderPattern;

    @Column(name = "match_type", nullable = false, length = 20)
    private String matchType = "EXACT";

    @Column(name = "keyword_pattern", length = 255)
    private String keywordPattern = "";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "action", nullable = false, length = 20)
    private String action = "collect";

    @Column(name = "priority", nullable = false)
    private int priority = 0;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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