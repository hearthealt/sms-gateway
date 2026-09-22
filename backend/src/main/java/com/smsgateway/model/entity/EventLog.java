package com.smsgateway.model.entity;

import com.smsgateway.model.enums.EventLevel;
import com.smsgateway.model.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条运行事件。
 *
 * <p><b>这张表里没有短信正文，也没有验证码</b> —— 与 {@link SmsMessage} 不同，
 * 它是长期留存的运行记录（默认 7 天），正文和验证码不该在里面出现第二遍。
 * 要内容按 {@link #smsMessageId} 关联回 sms_message（那张表有自己的过期策略）。
 *
 * <p><b>也没有 device_token / enroll_secret</b>：令牌可以冒充设备，写进任何长期留存的
 * 地方都是净损失。认证失败只记「令牌无效」这个结论（见 {@code DeviceAuthInterceptor}）。
 *
 * <p>{@link #deviceCode} 冗余存业务标识而不是只存外键：设备被删之后（删设备会连同
 * 它的历史事件一起清掉，但「删除」这个动作本身要留痕）列表上仍然认得出是什么设备。
 * 同理 {@link #smsMessageId} 可能指向一条已被保留策略清掉的短信，展示时必须容错。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_log")
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private EventLevel level = EventLevel.INFO;

    /** 关联 sms_device.id。设备已删或未认出是哪台设备时为 null。 */
    @Column(name = "device_id")
    private Long deviceId;

    /** 设备的业务标识（UUID）。设备行不在了也认得出。 */
    @Column(name = "device_code", length = 128)
    private String deviceCode;

    /** 设备侧的消息 ID，用于与 sms_message 对照。 */
    @Column(name = "local_message_id", length = 128)
    private String localMessageId;

    /** 关联 sms_message.id；那一行可能已被保留策略清掉。 */
    @Column(name = "sms_message_id")
    private Long smsMessageId;

    @Column(name = "sender", length = 100)
    private String sender;

    @Column(name = "phone", length = 32)
    private String phone;

    /** 判定结果 / 原因码 / HTTP 状态。**不得写入正文或验证码**。 */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        // 允许调用方显式指定时刻（补记、测试），缺省才是「现在」。
        // 也能挡住「先把实体建好、过一会儿才 save」时时刻漂移。
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (level == null) {
            level = EventLevel.INFO;
        }
    }
}
