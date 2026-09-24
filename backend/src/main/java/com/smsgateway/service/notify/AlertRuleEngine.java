package com.smsgateway.service.notify;

import com.smsgateway.model.entity.AlertRule;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.repository.AlertRuleRepository;
import com.smsgateway.repository.NotifyChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警路由：什么坏了发给谁。
 *
 * <p>与 {@link NotifyRouteEngine} 同构，但匹配的维度是「告警类型 + 设备」，
 * 而不是短信的发送方/关键词/号码。
 *
 * <p>与它一样是**并集语义**：一条告警可以同时进「运维群」和「我的微信」，
 * 所以遍历全部规则、把所有命中的渠道合起来，不短路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleEngine {

    private final AlertRuleRepository ruleRepository;
    private final NotifyChannelRepository channelRepository;

    /**
     * 这条告警该发到哪些渠道。
     *
     * <p>返回的集合**只包含「存在且启用」的渠道**，理由与
     * {@link NotifyRouteEngine#matchChannels} 完全相同：留给调度器去跳过的记录
     * 会一条条堆在库里，然后被清扫改成「已取消」，只在投递记录页上刷屏。
     *
     * @return 命中的渠道 id。用 {@link LinkedHashSet} 去重：两条规则可能指向同一个渠道。
     */
    public Set<Long> matchChannels(AlertType type, String subjectKey) {
        Set<Long> matched = new LinkedHashSet<>();

        for (AlertRule rule : ruleRepository.findByEnabledTrue()) {
            if (matches(rule, type, subjectKey)) {
                matched.addAll(rule.getChannelIds());
            }
        }

        if (matched.isEmpty()) {
            return matched;
        }

        return channelRepository.findAllById(matched).stream()
                .filter(NotifyChannel::isEnabled)
                .map(NotifyChannel::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean matches(AlertRule rule, AlertType type, String subjectKey) {
        // 不限类型（null）表示这条规则对所有告警生效
        if (rule.getAlertType() != null && rule.getAlertType() != type) {
            return false;
        }

        String limitedDevice = rule.getDeviceId();
        if (limitedDevice == null || limitedDevice.isBlank()) {
            return true;
        }

        // 限定了设备号的规则**只对设备类主体生效**。
        //
        // 反过来的做法（让 channel:7 也命中）会把「只为某台设备配的渠道」拿去收
        // 一条渠道告警 —— 那是把告警发到了错误的地方，而配置的人从界面上看不出
        // 自己的规则会收到这类东西。同理，设备类主体必须精确相等（设备号是标识符）。
        return subjectKey != null && limitedDevice.equals(AlertSubjects.deviceCodeOf(subjectKey));
    }
}
