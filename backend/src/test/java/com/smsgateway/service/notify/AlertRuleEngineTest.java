package com.smsgateway.service.notify;

import com.smsgateway.model.entity.AlertRule;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.repository.AlertRuleRepository;
import com.smsgateway.repository.NotifyChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 告警路由的匹配。
 *
 * <p>重点是两条「发错地方」的防线：限定了设备号的规则**不该**去收渠道告警
 * （配置的人从界面上看不出自己的规则会收到这类东西），设备号必须精确相等
 * （它是标识符，不是模式）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertRuleEngineTest {

    @Mock private AlertRuleRepository ruleRepository;
    @Mock private NotifyChannelRepository channelRepository;

    @InjectMocks private AlertRuleEngine engine;

    private AlertRule rule(Long id, AlertType type, String deviceId, Long... channelIds) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setRuleName("规则" + id);
        rule.setAlertType(type);
        rule.setDeviceId(deviceId);
        rule.setChannelIds(new java.util.LinkedHashSet<>(List.of(channelIds)));
        return rule;
    }

    private NotifyChannel channel(Long id, boolean enabled) {
        NotifyChannel channel = new NotifyChannel();
        channel.setId(id);
        channel.setName("渠道" + id);
        channel.setEnabled(enabled);
        return channel;
    }

    @Test
    @DisplayName("类型为 null 的规则匹配所有告警类型")
    void nullTypeMatchesEverything() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule(1L, null, null, 10L)));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(10L, true)));

        assertThat(engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abc")).containsExactly(10L);
        assertThat(engine.matchChannels(AlertType.CHANNEL_AUTO_DISABLED, "channel:7")).containsExactly(10L);
    }

    @Test
    @DisplayName("类型不匹配的规则不命中")
    void typeMustMatch() {
        when(ruleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule(1L, AlertType.DEVICE_OFFLINE, null, 10L)));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(10L, true)));

        assertThat(engine.matchChannels(AlertType.CHANNEL_AUTO_DISABLED, "channel:7")).isEmpty();
        assertThat(engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abc")).containsExactly(10L);
    }

    @Test
    @DisplayName("设备号精确相等 —— abc 不该匹配 abcd")
    void deviceMatchIsExact() {
        when(ruleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule(1L, null, "android-abc", 10L)));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(10L, true)));

        assertThat(engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abcd")).isEmpty();
        assertThat(engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abc")).containsExactly(10L);
    }

    @Test
    @DisplayName("限定了设备号的规则**不**收渠道告警 —— 那会把告警发到错误的地方")
    void deviceLimitedRuleDoesNotMatchChannelSubject() {
        when(ruleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule(1L, AlertType.CHANNEL_AUTO_DISABLED, "android-abc", 10L)));

        // 类型对得上、设备号也「像」，但主体是 channel:... —— 必须不命中。
        // 命中意味着这条渠道会收到一堆关于某台设备的告警，而配置的人完全没这个预期。
        assertThat(engine.matchChannels(AlertType.CHANNEL_AUTO_DISABLED, "channel:7")).isEmpty();
    }

    @Test
    @DisplayName("停用或已删除的渠道被滤掉 —— 否则那行记录只会在投递记录页上刷屏")
    void filtersUnavailableChannels() {
        when(ruleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule(1L, null, null, 10L, 11L, 99L)));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(10L, true), channel(11L, false)));

        // 11 停用、99 已被删（查不出来），只剩 10
        assertThat(engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abc")).containsExactly(10L);
    }

    @Test
    @DisplayName("两条规则指向同一渠道时去重")
    void deduplicatesChannels() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                rule(1L, null, null, 10L),
                rule(2L, AlertType.DEVICE_OFFLINE, null, 10L)));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(10L, true)));

        Set<Long> matched = engine.matchChannels(AlertType.DEVICE_OFFLINE, "device:android-abc");

        // 重复的投递任务会在 uk 上撞唯一约束
        assertThat(matched).containsExactly(10L);
    }
}
