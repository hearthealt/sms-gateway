package com.smsgateway.service.notify;

import com.smsgateway.model.dto.AlertRuleRequest;
import com.smsgateway.model.dto.AlertRuleView;
import com.smsgateway.model.entity.AlertRule;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.repository.AlertRuleRepository;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.service.AdminEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警规则的增删改查。
 *
 * <p>结构与 {@link NotifyRouteService} 同构，连校验的话术都对得上 ——
 * 「一条规则至少要配一个渠道」在这里同样成立，而且更致命：一条没有目标的告警规则
 * 看起来配好了，出事时一条通知都收不到，而那正是「失败可见性」要消灭的情况。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository ruleRepository;
    private final NotifyChannelRepository channelRepository;
    private final AdminEventBroadcaster adminEvents;

    public List<AlertRuleView> list() {
        Map<Long, NotifyChannel> channels = channelMap();
        return ruleRepository.findAll().stream()
                .map(rule -> toView(rule, channels))
                .toList();
    }

    /** 下拉框选项。管理端从后端取，前端不硬编码（同 EventType 的做法）。 */
    public List<Map<String, String>> types() {
        return java.util.Arrays.stream(AlertType.values())
                .map(type -> Map.of("value", type.name(), "label", type.label()))
                .toList();
    }

    @Transactional
    public AlertRuleView create(AlertRuleRequest request) {
        AlertRule rule = new AlertRule();
        applyFields(rule, request, true);
        ruleRepository.save(rule);
        log.info("新建告警规则：{} → {} 个渠道", rule.getRuleName(), rule.getChannelIds().size());
        notifyChanged();
        return toView(rule, channelMap());
    }

    @Transactional
    public AlertRuleView update(Long id, AlertRuleRequest request) {
        AlertRule rule = require(id);
        applyFields(rule, request, false);
        ruleRepository.save(rule);
        notifyChanged();
        return toView(rule, channelMap());
    }

    @Transactional
    public void delete(Long id) {
        ruleRepository.delete(require(id));
        notifyChanged();
    }

    @Transactional
    public AlertRuleView setEnabled(Long id, boolean enabled) {
        AlertRule rule = require(id);

        // 与转发规则同一条守卫，理由也一样：没有目标的规则「开着」却什么都不发。
        if (enabled && rule.getChannelIds().isEmpty()) {
            throw new IllegalArgumentException(
                    "这条规则没有任何投递目标，无法启用。请先在「转发渠道」页确认渠道还在，"
                            + "然后编辑这条规则重新选择目标。");
        }

        rule.setEnabled(enabled);
        ruleRepository.save(rule);
        notifyChanged();
        return toView(rule, channelMap());
    }

    private void applyFields(AlertRule rule, AlertRuleRequest request, boolean isCreate) {
        if (request.getRuleName() != null && !request.getRuleName().isBlank()) {
            rule.setRuleName(request.getRuleName().trim());
        } else if (isCreate) {
            throw new IllegalArgumentException("规则名不能为空");
        }

        // 空串表示「不限类型」。**不把空串当成解析失败**：界面上那个下拉的「全部类型」
        // 提交上来就是一个空值，它表达的是「不限制」而不是「写错了」。
        if (request.getAlertType() != null) {
            rule.setAlertType(parseType(request.getAlertType()));
        }

        if (request.getDeviceId() != null) {
            rule.setDeviceId(blankToNull(request.getDeviceId()));
        }

        if (request.getChannelIds() != null) {
            // 去重：同一个渠道提交两次会在关联表的主键上撞唯一约束
            LinkedHashSet<Long> ids = new LinkedHashSet<>(request.getChannelIds());
            if (ids.isEmpty()) {
                throw new IllegalArgumentException(
                        "一条告警规则至少要配一个渠道 —— 没有目标的规则永远不会发出任何告警，"
                                + "而它在列表上看起来和正常规则一模一样。");
            }
            for (Long channelId : ids) {
                if (!channelRepository.existsById(channelId)) {
                    throw new IllegalArgumentException("渠道不存在: " + channelId);
                }
            }
            rule.setChannelIds(ids);
        } else if (isCreate) {
            throw new IllegalArgumentException("一条告警规则至少要配一个渠道");
        }

        rule.setEnabled(request.isEnabled());
    }

    /**
     * 字符串 → 告警类型，空串给 null（不限类型）。
     *
     * <p>不用 Jackson 直接把请求体绑成枚举：一个拼错的名字会在反序列化阶段抛
     * {@code HttpMessageNotReadableException}，而全局异常处理器没有接它 ——
     * 管理端看到 500，而真相只是类型名打错了。
     */
    private AlertType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AlertType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的告警类型：" + raw);
        }
    }

    private AlertRule require(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + id));
    }

    private Map<Long, NotifyChannel> channelMap() {
        return channelRepository.findAll().stream()
                .collect(Collectors.toMap(NotifyChannel::getId, Function.identity(), (a, b) -> a));
    }

    private AlertRuleView toView(AlertRule rule, Map<Long, NotifyChannel> channels) {
        List<Long> ids = new ArrayList<>(rule.getChannelIds());
        List<String> names = ids.stream()
                .map(id -> {
                    NotifyChannel channel = channels.get(id);
                    if (channel == null) {
                        return "（渠道已删除）";
                    }
                    return channel.isEnabled() ? channel.getName() : channel.getName() + "（已停用）";
                })
                .toList();

        AlertRuleView view = new AlertRuleView();
        view.setId(rule.getId());
        view.setRuleName(rule.getRuleName());
        view.setAlertType(rule.getAlertType() == null ? null : rule.getAlertType().name());
        // 类型为空时说「不限类型」而不是留空：界面上一格空白看不出是「不限」还是「没读到」
        view.setAlertTypeLabel(rule.getAlertType() == null ? "不限类型" : rule.getAlertType().label());
        view.setDeviceId(rule.getDeviceId());
        view.setEnabled(rule.isEnabled());
        view.setChannelIds(ids);
        view.setTargetChannelNames(names);
        view.setCreateTime(rule.getCreatedAt());
        return view;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 告警规则页的「该刷新了」信号（覆盖「另一个管理员改了」这一种情况）。 */
    private void notifyChanged() {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_ALERT_RULES, Map.of());
    }
}
