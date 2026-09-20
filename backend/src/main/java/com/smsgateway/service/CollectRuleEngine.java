package com.smsgateway.service;

import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.repository.CollectRuleRepository;
import com.smsgateway.util.RuleMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 采集规则引擎：把 sms_collect_rule 表里配置的规则真正应用到接收流程。
 *
 * <p>匹配约定：
 * <ul>
 *   <li>一条规则命中 = sender 命中 且（keyword 为空 或 keyword 命中正文）</li>
 *   <li>matchType 对 sender 与 keyword 共用（实体只有一个 match_type 字段），
 *       因此「任意发送方」在 LIKE 规则里写 {@code %}，在 REGEX 规则里写 {@code .*}</li>
 *   <li>REGEX 用 {@code find()} 做包含匹配，不是整体匹配 ——
 *       否则规则 {@code 验证码} 永远匹配不到「您的验证码是123456」</li>
 *   <li>priority 降序，首条命中即定论；全部不命中走默认 collect（保底不丢验证码）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectRuleEngine {

    public static final String ACTION_IGNORE = "ignore";

    private final CollectRuleRepository collectRuleRepository;

    /**
     * 规则判定结果。
     *
     * @param ignored    是否命中 ignore 规则
     * @param matchedRule 命中的规则名，无命中时为 null（便于排查「为什么这条被忽略了」）
     */
    public record Decision(boolean ignored, String matchedRule) {

        static final Decision DEFAULT_COLLECT = new Decision(false, null);
    }

    /**
     * 按优先级降序逐条匹配，返回首条命中的规则动作；全部不命中则采集。
     */
    public Decision decide(String sender, String content) {
        List<SmsCollectRule> rules = collectRuleRepository.findByEnabledTrueOrderByPriorityDesc();

        for (SmsCollectRule rule : rules) {
            if (matches(rule, sender, content)) {
                boolean ignored = ACTION_IGNORE.equalsIgnoreCase(rule.getAction());
                log.debug("Collect rule matched: sender={}, rule={}, action={}",
                        sender, rule.getRuleName(), rule.getAction());
                return new Decision(ignored, rule.getRuleName());
            }
        }
        return Decision.DEFAULT_COLLECT;
    }

    private boolean matches(SmsCollectRule rule, String sender, String content) {
        if (!matchesValue(rule.getSenderPattern(), rule.getMatchType(), sender)) {
            return false;
        }

        String keyword = rule.getKeywordPattern();
        if (keyword == null || keyword.isBlank()) {
            // 关键词为空表示不约束正文，只看发送方
            return true;
        }
        return matchesValue(keyword, rule.getMatchType(), content);
    }

    /**
     * 具体的比较交给 {@link RuleMatcher} —— 转发规则用的是同一套。
     *
     * <p>注意这里**不能**换成 {@code RuleMatcher.matchesOptional}：那个把「空模式」
     * 当作「不限制」而返回命中，而本方法要保持「空模式不命中」的原语义。
     * 「关键词为空」这一情形上面已经单独判过了。
     */
    private boolean matchesValue(String pattern, String matchType, String value) {
        return RuleMatcher.matches(pattern, matchType, value);
    }
}
