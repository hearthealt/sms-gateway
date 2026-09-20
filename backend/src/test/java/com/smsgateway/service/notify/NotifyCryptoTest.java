package com.smsgateway.service.notify;

import com.smsgateway.config.NotifyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道凭据的加解密。
 *
 * <p>与 api_key 的明文存储刻意相反：webhook 地址创建时贴一次就够，
 * 没有任何需要回显明文的场景，所以能做加密就做。
 */
class NotifyCryptoTest {

    private static NotifyProperties propertiesWithKey() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEncryptKey(randomKey());
        return properties;
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    @DisplayName("加密后能解回原文")
    void roundTrip() {
        NotifyCrypto crypto = new NotifyCrypto(propertiesWithKey());
        String plaintext = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7xxx";

        String cipher = crypto.encrypt(plaintext);

        assertThat(cipher).isNotEqualTo(plaintext).doesNotContain("qyapi.weixin.qq.com");
        assertThat(crypto.decrypt(cipher)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("同一明文两次加密结果不同 —— IV 每次随机，GCM 下 IV 复用会毁掉安全性")
    void encryptIsNonDeterministic() {
        NotifyCrypto crypto = new NotifyCrypto(propertiesWithKey());

        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    @DisplayName("换了密钥就解不开 —— 必须抛错，绝不能回退成「当明文用」")
    void failsLoudlyWithWrongKey() {
        NotifyCrypto first = new NotifyCrypto(propertiesWithKey());
        String cipher = first.encrypt("secret-url");

        NotifyCrypto second = new NotifyCrypto(propertiesWithKey());

        // 回退成明文会把一段乱码当成 webhook 地址去请求，排查时完全看不出原因
        assertThatThrownBy(() -> second.decrypt(cipher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重新配置");
    }

    @Test
    @DisplayName("密文被改动也解不开（GCM 自带完整性校验）")
    void detectsTampering() {
        NotifyCrypto crypto = new NotifyCrypto(propertiesWithKey());
        String cipher = crypto.encrypt("secret-url");

        byte[] bytes = Base64.getDecoder().decode(cipher);
        bytes[bytes.length - 1] ^= 0x01;   // 翻最后一位
        String tampered = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("密钥长度不对拒绝启动 —— 32 字节是 AES-256 的要求")
    void rejectsWrongKeyLength() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEncryptKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new NotifyCrypto(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("没配密钥时构造不报错 —— 转发是可选能力，不该拦住整个应用启动")
    void constructionDoesNotRequireKey() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEncryptKey(null);

        // 启动阶段不该因为这个失败。总开关在库里、随时能打开，所以「你会不会用转发」
        // 在启动那一刻根本无从判断；把整个应用拦下来只会让不用转发的人也受影响。
        NotifyCrypto crypto = new NotifyCrypto(properties);

        assertThat(crypto.isReady()).isFalse();
    }

    @Test
    @DisplayName("没配密钥时用它会报错，且消息里写清该设哪个变量")
    void encryptFailsWithActionableMessageWhenKeyMissing() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEncryptKey(null);

        NotifyCrypto crypto = new NotifyCrypto(properties);

        // 这条断言的重点不是「抛错了」，而是**消息本身就是操作指引**。
        // 原实现只写「消息转发未启用」，而它经兜底的异常处理器到使用者眼前
        // 只剩一句「服务器内部错误」—— 现场完全想不到是要去设环境变量。
        assertThatThrownBy(() -> crypto.encrypt("x"))
                .isInstanceOf(NotifyNotConfiguredException.class)
                .hasMessageContaining("NOTIFY_ENCRYPT_KEY")
                .hasMessageContaining("openssl");
    }

    @Test
    @DisplayName("配了密钥就 isReady —— 管理端据此决定要不要显示配置指引")
    void readyWhenKeyConfigured() {
        assertThat(new NotifyCrypto(propertiesWithKey()).isReady()).isTrue();
    }

    @Test
    @DisplayName("配了密钥但格式不对仍然拒绝启动 —— 那是明确的配置错误，不该被静默降级")
    void refusesToStartWithMalformedKey() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEncryptKey("这不是-base64");

        // 与「没配」刻意区别对待：你既然填了，就是打算用它；填错了要当场说，
        // 而不是降级成「功能没反应」让人去猜。
        assertThatThrownBy(() -> new NotifyCrypto(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
