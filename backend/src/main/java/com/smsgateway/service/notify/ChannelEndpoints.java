package com.smsgateway.service.notify;

import java.net.URI;

/**
 * 渠道地址的域名白名单校验。
 *
 * <p>OWASP 的原话：「Deny-lists are bypass-prone. Prefer allow-lists.」对企微 / 飞书 /
 * 钉钉 / Telegram / Slack 这些**固定对接方**，白名单加得起来，就不该只依赖
 * {@link SsrfGuard} 那份黑名单 —— 白名单根本不给填别的域名的机会。
 *
 * <p>只有 {@code GENERIC_WEBHOOK} 因为要对接任意内部系统，才只能退到黑名单。
 *
 * <p>校验不过一律抛 {@link MissingConfigException}（终态、不重试）：地址填错了，
 * 重试一百次也不会变对，而用 {@code SendResult.failed(0, ...)} 表达会被分类器
 * 误判成「没到达对端、可重试」，于是一条配错的渠道会一直重试到把配额烧光。
 */
public class ChannelEndpoints {

    private ChannelEndpoints() {
    }

    /**
     * 校验地址的 host 在白名单内，返回解析好的 URI。
     *
     * @param channelName 出错时用来指路的渠道名（现场要看得懂是哪一项配错了）
     */
    public static URI requireHost(String url, String channelName, String... allowedHosts) {
        if (url == null || url.isBlank()) {
            throw new MissingConfigException(channelName + "：没有配置地址");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new MissingConfigException(channelName + "：地址格式不合法（" + e.getMessage() + "）");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new MissingConfigException(channelName + "：只支持 http/https，收到 " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new MissingConfigException(channelName + "：地址里没有主机名");
        }

        for (String allowed : allowedHosts) {
            if (host.equalsIgnoreCase(allowed)) {
                return uri;
            }
        }

        throw new MissingConfigException(
                channelName + "：地址必须是 " + String.join(" 或 ", allowedHosts)
                        + " 下的，当前填的是 " + NotifyRedactor.safeEndpoint(url));
    }
}
