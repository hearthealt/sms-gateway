package com.smsgateway.service.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护：发送前校验目标地址不在内网 / 元数据服务上。
 *
 * <p>只有 {@code GENERIC_WEBHOOK} 需要它 —— 那是唯一允许管理员填任意 URL 的渠道。
 * 但正因为如此它必须做：**管理后台账号一旦被盗，这个功能就是一个现成的内网探测器**，
 * 攻击者可以拿它去打 169.254.169.254 拿云厂商的临时凭证，或者扫内网服务。
 *
 * <p>几条不能省的实现要点：
 * <ul>
 *   <li><b>解析出全部地址逐个校验</b>，不只查第一个。DNS 可以返回多个 A 记录，
 *       也可以被构造成「第一次返回公网地址骗过校验、第二次返回内网地址」——
 *       这就是 DNS rebinding。</li>
 *   <li><b>不跟随重定向</b>：校验的是我们解析出来的地址，而对端可以 302 到内网。
 *       禁跟随在 {@link com.smsgateway.service.notify.NotifyHttpClient} 里做。</li>
 *   <li>校验发生在**每次发送前**，不是保存配置时。DNS 记录会变，
 *       存的时候通过不代表发的时候还通过。</li>
 * </ul>
 *
 * <p>注意这是**黑名单**。OWASP 明确说过黑名单是可以被绕过的，优先用白名单 ——
 * 对企微这类固定对接方，白名单在 {@link WecomBotSender} 里做（只认 qyapi.weixin.qq.com）。
 * 通用 webhook 没有固定域名可用，才只能退到黑名单。
 */
@Slf4j
@Component
public class SsrfGuard {

    /** 云厂商元数据服务的域名。解析后通常是 169.254.169.254，但那一步由下面的段判断兜住。 */
    private static final String[] BLOCKED_HOSTNAMES = {
            "metadata.google.internal",
            "metadata.goog"
    };

    /** 100.64.0.0/10 —— 运营商级 NAT，isSiteLocalAddress() 不覆盖它。 */
    private static final int CGNAT_FIRST_OCTET = 100;
    private static final int CGNAT_MIN_SECOND = 64;
    private static final int CGNAT_MAX_SECOND = 127;

    /**
     * @param allowPrivateNetwork 显式放行内网。对接公司内部系统是**合理需求**，
     *        所以留了这个口子，但默认关，打开时管理端要二次确认并记审计日志。
     * @throws SsrfBlockedException 校验不通过
     */
    public void validate(String url, boolean allowPrivateNetwork) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new SsrfBlockedException("地址格式不合法：" + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfBlockedException("只允许 http/https，收到：" + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("地址里没有主机名");
        }

        if (allowPrivateNetwork) {
            log.warn("SSRF 校验被显式放行（allow_private_network=true），目标主机：{}", host);
            return;
        }

        for (String blocked : BLOCKED_HOSTNAMES) {
            if (host.equalsIgnoreCase(blocked)) {
                throw new SsrfBlockedException("目标是云厂商元数据服务，禁止访问：" + host);
            }
        }

        InetAddress[] addresses;
        try {
            // 解析**全部** A + AAAA 记录
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("域名解析失败：" + host);
        }

        if (addresses.length == 0) {
            throw new SsrfBlockedException("域名没有解析到任何地址：" + host);
        }

        // 逐个校验，任意一个落在禁区就整体拒绝 —— 这正是防 DNS rebinding 的地方：
        // 只要有一个地址是内网，就说明这个域名可能被我们连到内网去。
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new SsrfBlockedException(
                        "目标解析到内网/保留地址，禁止访问：" + host + " → " + address.getHostAddress());
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        // 回环、任意地址（0.0.0.0）、链路本地（含 169.254/16 与 fe80::/10）、
        // 站点本地（10/8、172.16/12、192.168/16）、组播
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        // IPv6 唯一本地地址 fc00::/7 —— isSiteLocalAddress 不管 IPv6 的这一段
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }

        // 100.64.0.0/10（CGNAT）
        if (bytes.length == 4
                && (bytes[0] & 0xFF) == CGNAT_FIRST_OCTET
                && (bytes[1] & 0xFF) >= CGNAT_MIN_SECOND
                && (bytes[1] & 0xFF) <= CGNAT_MAX_SECOND) {
            return true;
        }

        return false;
    }
}
