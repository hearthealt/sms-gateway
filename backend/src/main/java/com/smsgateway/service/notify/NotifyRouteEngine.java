package com.smsgateway.service.notify;

import com.smsgateway.model.entity.NotifyRoute;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyRouteRepository;
import com.smsgateway.util.RuleMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 转发路由：哪些短信发到哪些渠道。
 *
 * <p>与 {@code CollectRuleEngine} 最关键的区别是**并集语义**：采集规则首条命中即定论
 * （采不采集是个二值判定），而这里一条短信可以同时进「运维群」和「我的微信」，
 * 所以是遍历全部规则、把所有命中的渠道合起来，**不短路**。
 *
 * <p>匹配条件的写法与采集规则完全一致（{@code %}/{@code _}/正则），共用
 * {@link RuleMatcher}，避免出现第二套 LIKE→正则的实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyRouteEngine {

    private final NotifyRouteRepository routeRepository;
    private final NotifyChannelRepository channelRepository;

    /**
     * 这条短信该发到哪些渠道。
     *
     * <p>返回的集合**只包含「存在且启用」的渠道**。过滤放在这里而不是留给调度器，
     * 是因为调度器那边只能「捞出来再跳过」—— 而跳过不掉的记录会一条条堆在库里：
     * 停用的渠道每收到一条匹配的短信就多一条待投递记录，然后被清扫改成「已取消」。
     * 那些行没有任何用处，只在投递记录页上刷屏。
     *
     * <p>过滤掉它们还顺带省掉一次无谓的插入 —— 上报路径上每条短信都会走到这里。
     *
     * @return 命中的渠道 id。用 {@link LinkedHashSet} 去重：两条规则可能指向同一个渠道，
     *         而重复的投递任务会在 {@code uk_sms_channel} 上撞唯一约束。
     */
    public Set<Long> matchChannels(SmsDevice device, SmsMessage message) {
        Set<Long> matched = new LinkedHashSet<>();

        for (NotifyRoute route : routeRepository.findByEnabledTrue()) {
            if (matches(route, device, message)) {
                matched.addAll(route.getChannelIds());
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

    private boolean matches(NotifyRoute route, SmsDevice device, SmsMessage message) {
        // 四个条件之间是「与」。空模式/空字段表示不限制这一项，用 matchesOptional。
        if (!RuleMatcher.matchesOptional(route.getSenderPattern(), route.getMatchType(), message.getSender())) {
            return false;
        }
        if (!RuleMatcher.matchesOptional(route.getKeywordPattern(), route.getMatchType(), message.getContent())) {
            return false;
        }
        if (!RuleMatcher.matchesOptional(route.getPhonePattern(), route.getMatchType(), message.getPhone())) {
            return false;
        }

        // 设备限定用**精确相等**，不走 matchType：设备号是 128 字符的标识符，
        // 用 LIKE 去匹配它只会让人误以为可以模糊查，而模糊匹配设备号在实际使用中
        // 没有场景（要限定就写全）。
        String routeDeviceId = route.getDeviceId();
        if (routeDeviceId != null && !routeDeviceId.isBlank()) {
            if (device == null || !routeDeviceId.equals(device.getDeviceId())) {
                return false;
            }
        }

        return true;
    }
}
