package com.smsgateway.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从短信正文里提取验证码。
 *
 * <p>从 {@code SmsService} 里搬出来的：它是一段自成一体、有明确输入输出的正则逻辑，
 * 留在那个已经很长的 service 里只会让「这条短信为什么没出码」更难查。
 */
public class CodeExtractor {

    /**
     * 验证码提取模式。分两类：关键词在前（「验证码是123456」）与关键词在后
     * （「123456 是您的验证码」）—— 后者原实现完全漏掉。
     *
     * <p>数字两侧的 {@code (?<!\d)} / {@code (?!\d)} 用于防止从更长的数字串里截取一段
     * （如订单号 1234567890 被当成验证码）。
     *
     * <p>这里刻意**不再**保留「任意 6 位数字」兜底：那会把订单号、快递单号
     * 误判成验证码，返回错码比返回空更糟。
     */
    private static final Pattern[] CODE_PATTERNS = {
            Pattern.compile(
                    "(?:验证码|校验码|动态码|安全码)[是为：:\\s]*(?<!\\d)(\\d{4,8})(?!\\d)"),
            Pattern.compile(
                    "(?<!\\d)(\\d{4,8})(?!\\d)\\s*(?:是|为)?\\s*(?:您的|你的)?\\s*(?:登录|注册|支付|校验|动态)?\\s*(?:验证码|校验码|动态码)"),
            Pattern.compile(
                    "(?:verification\\s+code|code)[是为：:\\s]*(?<!\\d)(\\d{4,8})(?!\\d)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "(?<!\\d)(\\d{4,8})(?!\\d)\\s*(?:is)?\\s*(?:your)?\\s*(?:verification\\s+code|code)",
                    Pattern.CASE_INSENSITIVE)
    };

    private CodeExtractor() {
    }

    /**
     * 从短信正文里取验证码。
     *
     * @return 取到时是纯数字串，取不到返回**空串**（不是 null）—— 调用方普遍直接拿去判空，
     *         返回 null 会在各处引出 NPE。这一约定是从原 SmsService 沿用下来的。
     */
    public static String extract(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (Pattern pattern : CODE_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }
}
