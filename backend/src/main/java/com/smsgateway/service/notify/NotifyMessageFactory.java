package com.smsgateway.service.notify;

import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * 把一条短信变成要发出去的消息。
 *
 * <p><b>转发出去的就是短信原文，不做模板、不打码。</b>所以这个类里没有渲染逻辑，
 * 只有「原样带走 + 顺带带上几个元信息」。刻意如此：
 *
 * <ul>
 *   <li><b>不要模板系统。</b>可配置模板是一整块配置面（占位符、默认值、前端表单、
 *       渲染异常），而个人短信网关没有「同一条短信要按不同渠道显示成不同样子」的需求。
 *       写死的格式够用，而写死的东西不会配错。</li>
 *   <li><b>不打码。</b>这意味着群里所有人（以及以后被拉进群的人）都能看到并能使用
 *       每一个验证码，而且没有运行时的提示信号。这是**配置渠道时就该知道的事**
 *       —— 谁在那个群里 —— 所以提示放在管理端的建渠道页，不在投递链路上拦。</li>
 * </ul>
 *
 * <p>元信息（发送方 / 设备 / 时间）仍带上：多台手机或双卡时，只看到一段正文根本
 * 分不清是哪台机器、哪个号收到的。它们不影响正文，包在最外层。
 * 真正「一条都不加」的话，企微那边收到的就是一段没有上下文的文字。
 */
@Component
@RequiredArgsConstructor
public class NotifyMessageFactory {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 是否附带来源信息，管理后台「系统设置」页可改，改完立即生效。 */
    private final SysConfigService sysConfigService;

    /**
     * 拼出要发出的正文。
     *
     * <p>格式写死：{@code 【设备 · 发送方 · 时间】\n原文}。设备读不到时自动省掉那一段，
     * 不会留一个孤零零的分隔符。
     */
    public RenderedMessage build(SmsMessage sms, String deviceName) {
        String content = sms.getContent() == null ? "" : sms.getContent();
        String sender = sms.getSender() == null ? "" : sms.getSender();
        String time = sms.getReceiveTime() == null ? "" : sms.getReceiveTime().format(TIME_FORMAT);

        // 来源信息可以在「系统设置」里关掉（只有一台手机、一张卡时它是噪音）
        if (!sysConfigService.getBoolean(SysConfigKey.NOTIFY_INCLUDE_META)) {
            return new RenderedMessage(content, sms.getId(), sms.getSender(), sms.getPhone(), deviceName, time);
        }

        // 先滤掉空的那几项再拼。**不能直接 String.join**：它对空字符串照样插分隔符，
        // 三项都读不到时会拼出一个孤零零的「【 ·  · 】」。
        String header = java.util.stream.Stream.of(deviceName, sender, time)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(" · "));

        String text = header.isEmpty() ? content : "【" + header + "】\n" + content;

        return new RenderedMessage(text, sms.getId(), sms.getSender(), sms.getPhone(), deviceName, time);
    }

    /**
     * 按 **UTF-8 字节数**截断，且不切断多字节字符。
     *
     * <p>企微与钉钉的长度限制是按字节算的（markdown ≤ 4096 字节），而**中文一个字 3 字节**
     * —— 4096 字节实际只有约 680 个汉字。更要命的是超长**静默截断、不报错**，
     * 所以必须我们自己截，否则消息会被对端从中间某个字节切掉，结尾是半截乱码。
     *
     * <p>而 Telegram / Slack 是按**字符**算的。用同一个函数套两边，中文长短信会在
     * 一侧被过度截断、另一侧被静默切半 —— 加那些渠道时要各自处理。
     */
    public static String truncateUtf8(String text, int maxBytes) {
        if (text == null) {
            return "";
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;

            // 留出省略号的位置，让结尾能看出「被截断了」而不是内容莫名其妙戛然而止
            if (used + bytes > maxBytes - 3) {
                break;
            }
            sb.appendCodePoint(codePoint);
            used += bytes;
            i += charCount;
        }
        return sb + "...";
    }

    /**
     * 按**字符数**截断（Telegram / Slack 的限额是按字符算的）。
     *
     * <p>与 {@link #truncateUtf8} 分开是有必要的：企微/钉钉按 UTF-8 字节限长，
     * 而中文一个字 3 字节 —— 拿字节函数去套 Telegram，一条 4000 字符的中文短信
     * 会在 1300 字左右就被砍掉，白白丢内容。
     */
    public static String truncateChars(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        // 用 codePointCount 避免把代理对（emoji 等）从中间切开
        int end = text.offsetByCodePoints(0, Math.max(0, maxChars - 3));
        return text.substring(0, end) + "...";
    }
}
