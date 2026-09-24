package com.smsgateway.service.notify;

import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警防刷屏的两个键。
 *
 * <p>这两个键的分工是整个告警特性里最容易被「简化」掉的一处 —— 合成一个键看起来
 * 更干净，而那样做的后果是「离线 → 恢复 → 离线」每轮都发一条，也就是防抖完全失效。
 * 所以下面把「恢复时清哪个、不清哪个」单独钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertGateTest {

    private static final String SUBJECT = "device:android-abc";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SysConfigService sysConfigService;

    @InjectMocks private AlertGate gate;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(sysConfigService.getInt(SysConfigKey.ALERT_COOLDOWN_MINUTES)).thenReturn(60);
    }

    private static final String EPISODE_KEY =
            "sms:alert:episode:DEVICE_OFFLINE:" + SUBJECT;
    private static final String COOLDOWN_KEY =
            "sms:alert:cooldown:DEVICE_OFFLINE:" + SUBJECT;

    @Test
    @DisplayName("两个键都空着时放行")
    void bothKeysFree() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(gate.shouldFire(AlertType.DEVICE_OFFLINE, SUBJECT)).isTrue();
    }

    @Test
    @DisplayName("已在故障期内则不发 —— 一次离线只告警一次")
    void episodeAlreadyOpen() {
        when(valueOperations.setIfAbsent(eq(EPISODE_KEY), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(gate.shouldFire(AlertType.DEVICE_OFFLINE, SUBJECT)).isFalse();
    }

    @Test
    @DisplayName("冷却没过则不发，且**把刚占上的故障期退回去**")
    void cooldownBlocksAndReleasesEpisode() {
        when(valueOperations.setIfAbsent(eq(EPISODE_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(COOLDOWN_KEY), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(gate.shouldFire(AlertType.DEVICE_OFFLINE, SUBJECT)).isFalse();

        // 不退的话，这次被冷却挡下的告警会白白消费掉这一次故障期 ——
        // 冷却过去之后设备再离线，episode 已经被占着，于是再也发不出来，
        // 而现场只会觉得「告警有时候不灵」。
        verify(redisTemplate).delete(EPISODE_KEY);
    }

    @Test
    @DisplayName("恢复在线只清故障期，**不清冷却** —— 清了就等于没有防抖")
    void clearEpisodeLeavesCooldown() {
        gate.clearEpisode(AlertType.DEVICE_OFFLINE, SUBJECT);

        verify(redisTemplate).delete(EPISODE_KEY);
        verify(redisTemplate, never()).delete(COOLDOWN_KEY);
    }

    @Test
    @DisplayName("Redis 挂掉时放行 —— 那恰恰是最需要告警的时刻")
    void redisFailurePassesThrough() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(gate.shouldFire(AlertType.DEVICE_OFFLINE, SUBJECT)).isTrue();
    }

    @Test
    @DisplayName("渠道类告警的故障期退回冷却窗口长度：它没有可观测的结束事件")
    void channelAlertsUseCooldownAsEpisodeTtl() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        gate.shouldFire(AlertType.CHANNEL_AUTO_DISABLED, "channel:7");

        // 设备离线的故障期是 7 天（有「恢复在线」这个事件来清它）；
        // 渠道自动停用没有对应事件，7 天会漏掉第二次故障，所以退回冷却窗口。
        verify(valueOperations).setIfAbsent(
                eq("sms:alert:episode:CHANNEL_AUTO_DISABLED:channel:7"), anyString(), eq(Duration.ofMinutes(60)));
    }
}
