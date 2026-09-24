package com.smsgateway.service.notify;

import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.NotifyDeliverySource;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.service.AdminEventBroadcaster;
import com.smsgateway.service.EventLogService;
import com.smsgateway.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警入队。
 *
 * <p>两条最重要的断言：**没有可用渠道时要留下事件**（那是「告警发不出去」唯一可见的地方），
 * 以及**任何异常都必须被吞掉**（告警是旁路能力，它出问题不该让设备离线判定、渠道停用
 * 这些正事失败）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertOutboxTest {

    private static final String SUBJECT = "device:android-abc";

    @Mock private SysConfigService sysConfigService;
    @Mock private AlertRuleEngine ruleEngine;
    @Mock private AlertGate alertGate;
    @Mock private NotifyDeliveryRepository deliveryRepository;
    @Mock private EventLogService eventLogService;
    @Mock private AdminEventBroadcaster adminEvents;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private AlertOutbox outbox;

    @BeforeEach
    void setUp() {
        when(sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)).thenReturn(true);
        when(alertGate.shouldFire(any(), anyString())).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("总开关关着时不投递，**但要留一条痕** —— 否则「配了规则却没通知」在日志里查不出原因")
    void disabledByMasterSwitchStillLeavesATrace() {
        when(sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        verify(ruleEngine, never()).matchChannels(any(), anyString());
        verify(deliveryRepository, never()).saveAll(any());
        // 这一条是踩过之后补的：管理员配好规则、设备真离线了，而运行日志里一个字都没有。
        // A3 的全部意义就是别让失败静默，这里恰好静默了。
        verify(eventLogService).record(eq(EventType.ALERT_UNDELIVERABLE), anyString());
    }

    @Test
    @DisplayName("「总开关没开」的提示一小时最多一条 —— 否则一台静默设备一天刷 1440 条")
    void switchOffNoticeIsThrottled() {
        when(sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)).thenReturn(false);
        // 第二次 setIfAbsent 返回 false 表示这一小时内已经记过了
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true, false);

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");
        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        verify(eventLogService, times(1)).record(eq(EventType.ALERT_UNDELIVERABLE), anyString());
    }

    @Test
    @DisplayName("Redis 挂掉时照常记录 —— 宁可多几条，也别把「为什么没告警」的答案一起吞掉")
    void switchOffNoticeSurvivesRedisFailure() {
        when(sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        verify(eventLogService).record(eq(EventType.ALERT_UNDELIVERABLE), anyString());
    }

    @Test
    @DisplayName("没有匹配到渠道时记一条 ALERT_UNDELIVERABLE —— 那是它唯一可见的地方")
    void noChannelsRecordsUndeliverable() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(Set.of());

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        verify(deliveryRepository, never()).saveAll(any());
        verify(eventLogService).record(eq(EventType.ALERT_UNDELIVERABLE), anyString());
    }

    @Test
    @DisplayName("被排除的渠道不出现在投递行里")
    void excludedChannelIsDropped() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(7L, 8L)));

        outbox.enqueue(AlertType.CHANNEL_AUTO_DISABLED, "channel:7", "渠道挂了", Set.of(7L));

        // 刚死掉的那个渠道必须排掉：不排的话那条投递会被「停用渠道清扫」改成已取消,
        // 也就是一条静默取消的告警 —— 而它本来是要去叫人的。
        ArgumentCaptor<List<NotifyDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(NotifyDelivery::getChannelId).containsExactly(8L);
    }

    @Test
    @DisplayName("排除后一个渠道都不剩时也记 ALERT_UNDELIVERABLE")
    void allChannelsExcludedRecordsUndeliverable() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(7L)));

        outbox.enqueue(AlertType.CHANNEL_AUTO_DISABLED, "channel:7", "渠道挂了", Set.of(7L));

        verify(deliveryRepository, never()).saveAll(any());
        verify(eventLogService).record(eq(EventType.ALERT_UNDELIVERABLE), anyString());
    }

    @Test
    @DisplayName("入队时带上来源与摘要，smsMessageId 留空")
    void deliveryCarriesAlertPayload() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(10L)));

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备「车间手机」已离线 32 分钟");

        ArgumentCaptor<List<NotifyDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(captor.capture());
        NotifyDelivery delivery = captor.getValue().get(0);
        assertThat(delivery.getSourceType()).isEqualTo(NotifyDeliverySource.ALERT);
        assertThat(delivery.getSmsMessageId()).isNull();
        assertThat(delivery.getAlertType()).isEqualTo(AlertType.DEVICE_OFFLINE);
        assertThat(delivery.getSubjectKey()).isEqualTo(SUBJECT);
        assertThat(delivery.getAlertSummary()).isEqualTo("设备「车间手机」已离线 32 分钟");
    }

    @Test
    @DisplayName("静默时段内：推迟到时段结束，**不丢弃**")
    void quietHoursPostponesDelivery() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(10L)));
        when(sysConfigService.getBoolean(SysConfigKey.ALERT_QUIET_HOURS_ENABLED)).thenReturn(true);
        // 造一个覆盖「现在」的时段：从一分钟前到一分钟后，必然落在里面
        LocalDateTime now = LocalDateTime.now();
        when(sysConfigService.get(SysConfigKey.ALERT_QUIET_HOURS_START))
                .thenReturn(now.minusMinutes(1).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        when(sysConfigService.get(SysConfigKey.ALERT_QUIET_HOURS_END))
                .thenReturn(now.plusMinutes(5).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        ArgumentCaptor<List<NotifyDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getNextRetryAt()).isAfter(now);
    }

    @Test
    @DisplayName("闸门说不发就不发")
    void gateBlocks() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(10L)));
        when(alertGate.shouldFire(any(), anyString())).thenReturn(false);

        outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线");

        verify(deliveryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("仓储抛异常被吞掉 —— 告警是旁路能力，不该让正事失败")
    void repositoryFailureIsSwallowed() {
        when(ruleEngine.matchChannels(any(), anyString())).thenReturn(new java.util.LinkedHashSet<>(List.of(10L)));
        when(deliveryRepository.saveAll(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> outbox.enqueue(AlertType.DEVICE_OFFLINE, SUBJECT, "设备离线"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("静默时段配置格式不对时按「没有静默时段」处理，而不是把所有告警永久推迟")
    void malformedQuietHoursFallsBackToImmediate() {
        assertThat(AlertOutbox.parseTimeValue("晚上十点")).isNull();
        assertThat(AlertOutbox.parseTimeValue("")).isNull();
        assertThat(AlertOutbox.parseTimeValue(null)).isNull();
        assertThat(AlertOutbox.parseTimeValue("22:00")).isEqualTo(java.time.LocalTime.of(22, 0));
    }
}
