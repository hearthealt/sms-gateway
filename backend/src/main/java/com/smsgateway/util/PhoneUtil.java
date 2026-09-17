package com.smsgateway.util;

/**
 * 手机号归一化，用于跨端匹配。
 *
 * <p>设备上报的号码来自 {@code TelephonyManager}，带不带 {@code +86}、有没有空格横线，
 * 完全取决于手机厂商；而等待验证码走的是 Redis key 精确匹配，两边格式只要差一个字符
 * 就会**静默超时**（不报参数错，调用方极难排查）。
 * 所以写入方（SmsService）和读取方（WaitingService）都必须先过这里。
 */
public final class PhoneUtil {

    private PhoneUtil() {
    }

    /**
     * 归一化为纯数字，并去掉中国大陆的 86 国家码。
     *
     * <pre>
     * "+86 138-0013-8000" → "13800138000"
     * "8613800138000"     → "13800138000"
     * "13800138000"       → "13800138000"
     * "+1 415-555-1234"   → "14155551234"   （11 位，不受影响）
     * null / ""           → ""
     * </pre>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 用 ASCII 判断而不是 Character.isDigit：后者对阿拉伯-印度数字等
            // 非 ASCII 数字也返回 true，那些字符进 Redis key 只会带来麻烦
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }

        String value = digits.toString();
        // 只剥「86 + 11 位号码」这种 13 位形态；13 位但不以 86 开头的不动
        if (value.length() == 13 && value.startsWith("86")) {
            return value.substring(2);
        }
        return value;
    }
}
