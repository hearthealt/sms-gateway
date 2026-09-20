package com.smsgateway.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则模式匹配：采集规则（{@link com.smsgateway.service.CollectRuleEngine}）与
 * 转发规则（{@code NotifyRouteEngine}）共用。
 *
 * <p>抽出来的理由不是「少写几行」，而是**避免第二套 LIKE→正则的实现出现分叉**。
 * 这类转换的坑很具体（元字符必须转义，否则规则里写 {@code .} 就变成通配；
 * {@code %} 与 {@code _} 的语义必须各自对齐 SQL），两处各写一遍的话，
 * 迟早有一处先被修好、另一处还留着旧行为，而两边的规则在管理端长得一模一样。
 * 项目里 {@code HashUtil} / {@code PhoneUtil} 也是同一个原则。
 *
 * <p>正则缓存也一并搬过来共享。规则表很小且改动极少，缓存不做失效处理 ——
 * 但改规则时同一模式串的旧编译结果会一直用到进程重启，这是已知取舍。
 */
@Slf4j
public class RuleMatcher {

    public static final String MATCH_EXACT = "EXACT";
    public static final String MATCH_LIKE = "LIKE";
    public static final String MATCH_REGEX = "REGEX";

    /** LIKE 模式里需要按字面量处理的字符。 */
    private static final String REGEX_META_CHARS = "\\^$.|?*+()[]{}";

    private static final int MAX_CACHED_PATTERNS = 256;
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private RuleMatcher() {
    }

    /**
     * 用给定的匹配方式比较一个值。
     *
     * <p><b>语义与从 CollectRuleEngine 里搬出来时完全一致</b>，包括这条容易被误读的：
     * {@code pattern} 为空**不算命中**。调用方若把「空模式」理解为「不限制这一项」，
     * 要在自己那侧先判掉 —— 采集规则与转发规则都各自这么做了。放在这里统一
     * 「空模式 = 命中」会把采集规则里那种写法悄悄改掉。
     *
     * @return 命中为 true；模式或值为 null、匹配方式未知、正则非法时一律 false
     */
    public static boolean matches(String pattern, String matchType, String value) {
        if (pattern == null || value == null) {
            return false;
        }

        String type = matchType == null ? MATCH_EXACT : matchType.trim().toUpperCase();
        return switch (type) {
            case MATCH_EXACT -> pattern.equals(value);
            // LIKE 与 REGEX 都用 find() 做**包含**匹配，不是整体匹配 ——
            // 否则规则「验证码」永远匹配不到「您的验证码是123456」。
            case MATCH_LIKE -> find(likeToRegex(pattern), value);
            case MATCH_REGEX -> find(pattern, value);
            default -> {
                log.warn("Unknown matchType '{}', treated as not matched", matchType);
                yield false;
            }
        };
    }

    /** 模式为空视为「不限制这一项」，命中。转发规则用它，采集规则不用（见 {@link #matches}）。 */
    public static boolean matchesOptional(String pattern, String matchType, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        return matches(pattern, matchType, value);
    }

    private static boolean find(String regex, String value) {
        Pattern pattern = compile(regex);
        return pattern != null && pattern.matcher(value).find();
    }

    /** 编译失败（管理员填了非法正则）时返回 null 并记日志，绝不让接收接口 500。 */
    private static Pattern compile(String regex) {
        Pattern cached = PATTERN_CACHE.get(regex);
        if (cached != null) {
            return cached;
        }

        try {
            Pattern compiled = Pattern.compile(regex, Pattern.DOTALL);
            if (PATTERN_CACHE.size() < MAX_CACHED_PATTERNS) {
                PATTERN_CACHE.put(regex, compiled);
            }
            return compiled;
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex in rule, treated as not matched: {}", regex, e);
            return null;
        }
    }

    /**
     * SQL LIKE 语义转正则：{@code %} 匹配任意长度，{@code _} 匹配单个字符，
     * 其余字符一律按字面量处理（必须转义，否则规则里写 {@code .} 或 {@code *} 会变成元字符）。
     */
    public static String likeToRegex(String like) {
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
