package com.smsgateway.service;

import com.smsgateway.model.dto.CollectRuleRequest;
import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.repository.CollectRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRuleService {

    private final CollectRuleRepository collectRuleRepository;
    private final AdminEventBroadcaster adminEvents;

    /**
     * 规则页的「该刷新了」信号。
     *
     * <p>采集规则是**数据入口策略**：另一个管理员改一条，正在看这一页的人如果不知道，
     * 会以为「短信被吞了」。这是这一页唯一需要实时的理由 —— 它自己没有服务端自变源。
     *
     * <p>只作信号，前端不解析内容；重复调用无副作用，所以增删改四处都发。
     */
    private void notifyChanged() {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_RULES, Map.of());
    }

    public List<SmsCollectRule> list() {
        return collectRuleRepository.findAllByOrderByPriorityDesc();
    }

    @Transactional
    public SmsCollectRule create(CollectRuleRequest request) {
        SmsCollectRule rule = new SmsCollectRule();
        apply(rule, request);
        collectRuleRepository.save(rule);
        log.info("Collect rule created: id={}, name={}", rule.getId(), rule.getRuleName());
        notifyChanged();
        return rule;
    }

    @Transactional
    public SmsCollectRule update(Long id, CollectRuleRequest request) {
        SmsCollectRule rule = find(id);
        apply(rule, request);
        collectRuleRepository.save(rule);
        log.info("Collect rule updated: id={}", id);
        notifyChanged();
        return rule;
    }

    @Transactional
    public void delete(Long id) {
        if (!collectRuleRepository.existsById(id)) {
            throw new IllegalArgumentException("规则不存在: " + id);
        }
        collectRuleRepository.deleteById(id);
        log.info("Collect rule deleted: id={}", id);
        notifyChanged();
    }

    @Transactional
    public SmsCollectRule setEnabled(Long id, boolean enabled) {
        SmsCollectRule rule = find(id);
        rule.setEnabled(enabled);
        collectRuleRepository.save(rule);
        log.info("Collect rule {} {}", id, enabled ? "enabled" : "disabled");
        notifyChanged();
        return rule;
    }

    private SmsCollectRule find(Long id) {
        return collectRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
    }

    private void apply(SmsCollectRule rule, CollectRuleRequest request) {
        rule.setRuleName(request.getRuleName());
        rule.setSenderPattern(request.getSenderPattern());
        rule.setKeywordPattern(request.getKeywordPattern());
        rule.setMatchType(request.getMatchType());
        rule.setAction(request.getAction());
        rule.setPriority(request.getPriority());
        rule.setEnabled(request.isEnabled());
        rule.setDescription(request.getDescription());
    }
}
