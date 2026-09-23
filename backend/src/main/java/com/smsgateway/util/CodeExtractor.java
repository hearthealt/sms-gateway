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
     * <p><b>码不一定是纯数字</b>：字母数字混排（{@code A3F9K2}、{@code 9K2M7P}）在真实
     * 短信里很常见，所以字符类是 {@code [A-Za-z0-9]} 而不是 {@code \d}。这一点很重要 ——
     * 客户端原先也认码，而它只认纯数字；现在提取只留这一处，
     * 这里认不出的码就是全链路都认不出的码。
     *
     * <p>字符类两侧的 {@code (?<![A-Za-z0-9])} / {@code (?![A-Za-z0-9])} 用于防止从更长的
     * 串里截取一段（订单号 {@code 1234567890}、或 {@code Order1234567}）——{@code 验证码是
     * Order1234} 会因边界而整体不匹配，而不是给出一个 8 字符的截断值。
     *
     * <p>但边界**挡不住纯字母的英文单词**，那要靠「必须含数字」（见 [TOKEN]）。
     * 这是两个独立的约束，缺一不可。
     *
     * <p>这里刻意**不再**保留「任意 6 位数字」兜底：那会把订单号、快递单号
     * 误判成验证码，返回错码比返回空更糟。
     *
     * <p>英文模式额外允许一个系动词（{@code is} / {@code are}）：{@code Your verification
     * code is 483920} 是最常见的英文写法，原先提取不到。它此前是被设备端那个裸 6 位兜底
     * 意外救回来的 —— 现在客户端不再认码，这个盲区必须在这里补上，否则会变成回归。
     * 加 `is` 不会误伤：码本身要求 4~8 位且有边界，{@code is} 只有 2 位，不可能被当成码。
     *
     * <p><b>已知不覆盖</b>（遇到真实样例再加，不要凭想象放宽）：带连字符/空格的形态
     * （{@code G-123456}、{@code AB 12 CD}）。放宽字符类到含分隔符会显著抬高误判率，
     * 而那些形态目前没有实例。
     */
    /**
     * 一个「码」。**必须含至少一位数字**。
     *
     * 放宽到 {@code [A-Za-z0-9]} 之后，光有边界是不够的 —— 纯字母的英文单词
     * 同样是 4~8 个字母、同样被空格围着：
     *
     * ```
     * Your verification code is required   → "required"
     * Your code sent: 483920              → "sent"（真正的码反而被跳过）
     * 【微信】您的 WeChat 验证码已发送        → "WeChat"
     * ```
     *
     * 「返回错码比返回空更糟」在这里是要付出代价的：这些错值会被写进
     * {@code sms:code:{phone}} 并推给等待方。要求一位数字即可挡住全部纯字母单词，
     * 而真实验证码——包括字母数字混排的 {@code A3F9K2}、{@code 9K2M7P}——都含数字。
     */
    private static final String TOKEN =
            "(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\\d)([A-Za-z0-9]{4,8})";
    private static final String TOKEN_START = "(?<![A-Za-z0-9])";
    private static final String TOKEN_END = "(?![A-Za-z0-9])";

    /** 中文关键词。繁体与「安全码」原先只在设备端的候选表里，提取端没有，等于白放行。 */
    private static final String CJK_KEYWORDS =
            "验证码|校验码|动态码|安全码|动态密码|认证码|驗證碼|認證碼|動態密碼";

    /**
     * 英文关键词。设备端 filter 已按这几条放行，这里不认就等于只放进了噪音。
     *
     * 两侧都要边界，但**都用「不是字母」而不是 {@code \b}**：
     *
     * - 前导 {@code (?<![A-Za-z])} 挡住 {@code postcode} / {@code encode} / {@code decode}。
     *   少了它，{@code Your postcode is 200000} 会因为词里含 {@code code} 而提取出 {@code 200000}。
     * - 后导 {@code (?![A-Za-z])} 挡住 {@code codes} / {@code passcodes}，同时**放行
     *   {@code code123456}** —— 用 {@code \b} 会在 {@code e} 与 {@code 1} 之间判为无边界，
     *   而这两个都是词字符。设备端 filter 用的正是「不是字母」，两边必须一致，
     *   否则结果是「上传了但出不了码」的纯噪音。
     */
    private static final String EN_KEYWORDS =
            "(?<![A-Za-z])(?:verification\\s+code|code|otp|pin|passcode)(?![A-Za-z])";

    /**
     * 关键词**之后**的码，前导守卫放宽成「前面不是数字」。
     *
     * 关键词自己的边界（CJK 天然是、英文靠 {@code (?![A-Za-z])}）已经保证了它的结束位置，
     * 所以这里不必再要求「前面不是字母数字」—— 那个要求会让 {@code code123456} 失配，
     * 因为关键词的最后一个字符 {@code e} 恰好是字母。中文侧的 {@code 验证码54869} 一直是好的
     * （「码」不是 ASCII 字母数字），英文侧是补上的。
     */
    private static final String TOKEN_START_AFTER_KEYWORD = "(?<![0-9])";

    private static final Pattern[] CODE_PATTERNS = {
            Pattern.compile(
                    "(?:" + CJK_KEYWORDS + ")[是为：:\\s]*"
                            + TOKEN_START_AFTER_KEYWORD + TOKEN + TOKEN_END),
            Pattern.compile(
                    TOKEN_START + TOKEN + TOKEN_END
                            + "\\s*(?:是|为)?\\s*(?:您的|你的)?\\s*(?:登录|注册|支付|校验|动态)?\\s*(?:"
                            + CJK_KEYWORDS + ")"),
            Pattern.compile(
                    EN_KEYWORDS + "[是为：:\\s]*(?:is|are)?[是为：:\\s]*"
                            + TOKEN_START_AFTER_KEYWORD + TOKEN + TOKEN_END,
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    TOKEN_START + TOKEN + TOKEN_END
                            + "\\s*(?:is)?\\s*(?:your)?\\s*" + EN_KEYWORDS,
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
