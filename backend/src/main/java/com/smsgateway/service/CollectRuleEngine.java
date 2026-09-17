package com.smsgateway.service;

import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.repository.CollectRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private static final String MATCH_EXACT = "EXACT";
    private static final String MATCH_LIKE = "LIKE";
    private static final String MATCH_REGEX = "REGEX";

    /** LIKE 模式里需要按字面量处理的字符。 */
    private static final String REGEX_META_CHARS = "\\^$.|?*+()[]{}";

    /** 已编译正则缓存，key 为模式串本身。规则表很小且改动极少，不做失效处理。 */
    private static final int MAX_CACHED_PATTERNS = 256;
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

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

    private boolean matchesValue(String pattern, String matchType, String value) {
        if (pattern == null || value == null) {
            return false;
        }

        String type = matchType == null ? MATCH_EXACT : matchType.trim().toUpperCase();
        return switch (type) {
            case MATCH_EXACT -> pattern.equals(value);
            case MATCH_LIKE -> find(likeToRegex(pattern), value);
            case MATCH_REGEX -> find(pattern, value);
            default -> {
                log.warn("Unknown matchType '{}' in collect rule, treated as not matched", matchType);
                yield false;
            }
        };
    }

    private boolean find(String regex, String value) {
        Pattern pattern = compile(regex);
        return pattern != null && pattern.matcher(value).find();
    }

    /** 编译失败（管理员填了非法正则）时返回 null 并记日志，绝不让接收接口 500。 */
    private Pattern compile(String regex) {
        Pattern cached = patternCache.get(regex);
        if (cached != null) {
            return cached;
        }

        try {
            Pattern compiled = Pattern.compile(regex, Pattern.DOTALL);
            if (patternCache.size() < MAX_CACHED_PATTERNS) {
                patternCache.put(regex, compiled);
            }
            return compiled;
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex in collect rule, treated as not matched: {}", regex, e);
            return null;
        }
    }

    /**
     * SQL LIKE 语义转正则：{@code %} 匹配任意长度，{@code _} 匹配单个字符，
     * 其余字符一律按字面量处理（必须转义，否则规则里写 {@code .} 或 {@code *} 会变成元字符）。
     */
    static String likeToRegex(String like) {
        StringBuilder regex = new StringBuilder(like.length() * 2);
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> {
                    if (REGEX_META_CHARS.indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        return regex.toString();
    }
}
