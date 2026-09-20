package com.smsgateway.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 防护。
 *
 * <p>只有通用 webhook 需要它，但正因为如此它不能失灵：**管理后台账号一旦被盗，
 * 这个功能就是一个现成的内网探测器** —— 拿它去打 169.254.169.254 就能取到
 * 云厂商的临时凭证。
 *
 * <p>全部用 IP 字面量，不走 DNS：测试不该依赖网络能不能解析某个域名。
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    @DisplayName("云厂商 metadata 地址被拒")
    void blocksCloudMetadata() {
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://metadata.google.internal/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("回环被拒 —— 拿它探本机的其他服务是最直接的一步")
    void blocksLoopback() {
        assertThatThrownBy(() -> guard.validate("http://127.0.0.1:8080/admin", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://[::1]/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://0.0.0.0/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("RFC1918 私网被拒")
    void blocksPrivateRanges() {
        assertThatThrownBy(() -> guard.validate("http://10.0.0.1/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://172.16.5.5/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://192.168.1.1/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("100.64/10（CGNAT）也被拒 —— isSiteLocalAddress 不覆盖这一段")
    void blocksCgnat() {
        assertThatThrownBy(() -> guard.validate("http://100.64.0.1/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://100.127.255.254/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("IPv6 唯一本地地址 fc00::/7 被拒 —— isSiteLocalAddress 也不覆盖 IPv6 这一段")
    void blocksIpv6UniqueLocal() {
        assertThatThrownBy(() -> guard.validate("http://[fd00::1]/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("http://[fc00::1]/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("非 http/https 协议被拒")
    void blocksOtherSchemes() {
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("gopher://evil.com/", false))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.validate("ftp://evil.com/", false))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("显式放行时内网地址才放过去 —— 对接内部系统是合理需求")
    void allowsPrivateWhenExplicitlyEnabled() {
        assertThatCode(() -> guard.validate("http://192.168.1.100:8080/hook", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("公网地址正常放行")
    void allowsPublicAddress() {
        assertThatCode(() -> guard.validate("https://1.1.1.1/hook", false))
                .doesNotThrowAnyException();
    }
}
