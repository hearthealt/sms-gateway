package com.smsgateway.service.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渠道配置的回显打码与「未改动」识别。
 *
 * <p>这两件事必须成对做，否则会出一个很隐蔽的事故：管理端回显打码值 → 管理员
 * 只改了渠道名就保存 → 打码值 {@code 693a****5aaa} 被当成新地址存进库 →
 * 渠道从此静默失效，而且**看不出是什么时候坏的**（保存那一刻一切正常）。
 *
 * <p>所以 {@link #merge} 会把「提交上来的值等于原值的打码形式」认成「没改」。
 */
public class NotifyConfigMasker {

    /** 值需要打码的键名特征。 */
    private static final String[] SENSITIVE_HINTS = {"url", "secret", "token", "key", "password", "passwd"};

    private NotifyConfigMasker() {
    }

    /** 回显用：敏感的字符串值替换成头尾各留 4 位的展示值。 */
    public static Map<String, Object> mask(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (value instanceof String text && isSensitive(key)) {
                masked.put(key, NotifyRedactor.maskSecret(text));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    /**
     * 把提交上来的配置与库里的原值合并。
     *
     * @param existing 库里的明文配置
     * @param incoming 管理端提交的配置
     * @return 合并结果：提交了空值或打码值的键，保留原值
     */
    public static Map<String, Object> merge(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        if (incoming == null || incoming.isEmpty()) {
            return merged;
        }

        incoming.forEach((key, value) -> {
            // 提交了 null / 空串 = 明确表示「这项不改」
            if (value == null || (value instanceof String s && s.isBlank())) {
                return;
            }
            // 提交的值等于原值的打码形式 = 管理员没动它，只是把回显值原样交了回来
            Object original = merged.get(key);
            if (original instanceof String originalText
                    && value instanceof String submitted
                    && submitted.equals(NotifyRedactor.maskSecret(originalText))) {
                return;
            }
            merged.put(key, value);
        });

        return merged;
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        for (String hint : SENSITIVE_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
