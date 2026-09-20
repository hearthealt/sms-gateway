package com.smsgateway.service.notify;

import java.util.regex.Pattern;

/**
 * 把将要落库 / 打日志的文本里的凭据抹掉。
 *
 * <p>为什么必须有这一层：webhook URL **本身就是凭证**（企微的 {@code ?key=} 就是全部
 * 鉴权）。而对端报错时经常把整个请求 URL 原样回显在响应体里，我们又习惯把响应体
 * 截一段存进 {@code last_error}、打进度日志 —— 于是密钥就被写进了数据库和日志文件，
 * 而且每次打开管理端列表都会再显示一遍。
 *
 * <p>这是「转发功能不该降低 v1 的安全水位」这条要求的具体落点：v1 的凭据要么在库里
 * 明文（api_key，那是刻意的取舍），要么只存哈希；转发不能成为第一个把凭据悄悄
 * 泄到日志里的地方。
 *
 * <p>纯静态、无状态，方便单测直接调。
 */
public class NotifyRedactor {

    /** 落在文本里的 URL。 */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\"'<>\\\\]+", Pattern.CASE_INSENSITIVE);

    /**
     * 常见的凭据查询参数。
     *
     * <p>名单来自各渠道的官方文档：企微 {@code key}、钉钉 {@code access_token}、
     * Server酱的 SendKey 与 WxPusher 的 {@code SPT}（这两个在**路径**上，
     * 由下面的 {@link #LONG_PATH_SEGMENT} 兜）。
     *
     * <p>前缀允许 `?` / `&` **或字符串开头**：有些对端报错时只回显裸查询串
     * （{@code key=xxx&other=1}），只认带分隔符的话这段就漏过去了。
     */
    private static final Pattern SENSITIVE_PARAM =
            Pattern.compile("(?i)((?:^|[?&])(?:key|access_token|token|secret|sign|signature|sendkey|send_key|spt|password|passwd|auth)=)[^&\\s\"']*");

    /**
     * 路径里的长随机串。
     *
     * <p>补参数名那一条的漏：Server酱的 {@code /<SendKey>.send}、WxPusher 的
     * {@code /api/send/message/<SPT>/...} 都是把凭据放在**路径**上的，按参数名匹配
     * 一个也拦不住。20 位以上的单段 {@code [A-Za-z0-9_-]} 几乎必然是随机令牌，
     * 正常路径段不会有这么长的连续串。
     */
    private static final Pattern LONG_PATH_SEGMENT =
            Pattern.compile("(?<=/)[A-Za-z0-9_-]{20,}(?=[/.?#\\s\"']|$)");

    private static final String MASK = "***";

    private NotifyRedactor() {
    }

    /**
     * 抹掉一段文本里的凭据。给 {@code last_error}、日志用。
     *
     * <p>对整段文本做，而不只是对 URL —— 对端的报错里 URL 可能嵌在一句话中间。
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = URL_PATTERN.matcher(text).replaceAll(matchResult -> {
            String url = matchResult.group();
            String redacted = SENSITIVE_PARAM.matcher(url).replaceAll("$1" + MASK);
            return LONG_PATH_SEGMENT.matcher(redacted).replaceAll(MASK);
        });

        // 不带 scheme 的裸查询串（有些对端只回显 "key=xxx&..."）
        return SENSITIVE_PARAM.matcher(result).replaceAll("$1" + MASK);
    }

    /**
     * 只保留 {@code scheme://host}，**整条路径与查询都丢掉**。
     *
     * <p>用在「这条投递发往哪里」这种要给人看的场合：host 足以定位是哪个渠道，
     * 而路径和查询里都可能藏着凭据。比 {@link #redact} 更狠，因为它不依赖
     * 「哪些参数名算敏感」这份名单 —— 名单总有漏的。
     */
    public static String safeEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return MASK;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    /**
     * 给管理端展示用的部分打码：保留头尾各 4 位。
     *
     * <p>比 {@link #redact} 的整段 {@code ***} 更适合回显：管理员要能认出
     * 「这是运维群那个 key，不是测试群那个」，全打成星号就认不出来了，
     * 于是他会去别处找完整的值 —— 那反而更容易泄露。
     *
     * <p>头尾各 4 位对随机密钥来说不足以还原（剩下一百多位未知），
     * 但足以区分。
     */
    public static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return MASK;
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /** 截断到上限，超出部分用省略号标记 —— 便于看出「这只是开头」。 */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    /** 落库前的统一处理：先抹凭据，再截断到列宽以内。 */
    public static String forStorage(String text, int maxLength) {
        return truncate(redact(text), maxLength);
    }
}
