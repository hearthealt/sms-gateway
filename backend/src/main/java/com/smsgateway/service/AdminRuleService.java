package com.smsgateway.service;

import com.smsgateway.model.dto.CollectRuleRequest;
import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.repository.CollectRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRuleService {

    private final CollectRuleRepository collectRuleRepository;

    public List<SmsCollectRule> list() {
        return collectRuleRepository.findAllByOrderByPriorityDesc();
    }

    @Transactional
    public SmsCollectRule create(CollectRuleRequest request) {
        SmsCollectRule rule = new SmsCollectRule();
        apply(rule, request);
        collectRuleRepository.save(rule);
        log.info("Collect rule created: id={}, name={}", rule.getId(), rule.getRuleName());
        return rule;
    }

    @Transactional
    public SmsCollectRule update(Long id, CollectRuleRequest request) {
        SmsCollectRule rule = find(id);
        apply(rule, request);
        collectRuleRepository.save(rule);
        log.info("Collect rule updated: id={}", id);
        return rule;
    }

    @Transactional
    public void delete(Long id) {
        if (!collectRuleRepository.existsById(id)) {
            throw new IllegalArgumentException("规则不存在: " + id);
        }
        collectRuleRepository.deleteById(id);
        log.info("Collect rule deleted: id={}", id);
    }

    @Transactional
    public SmsCollectRule setEnabled(Long id, boolean enabled) {
        SmsCollectRule rule = find(id);
        rule.setEnabled(enabled);
        collectRuleRepository.save(rule);
        log.info("Collect rule {} {}", id, enabled ? "enabled" : "disabled");
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
