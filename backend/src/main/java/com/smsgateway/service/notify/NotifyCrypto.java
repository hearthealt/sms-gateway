package com.smsgateway.service.notify;

import com.smsgateway.config.NotifyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 渠道凭据的加解密（AES-256-GCM）。
 *
 * <p>与 {@code api_key} 的明文存储刻意相反。那里必须明文，因为管理端要支持
 * 「点显示看完整值」；而 webhook 地址创建时贴一次就够，之后只需要知道「配好了」，
 * **没有任何需要回显明文的场景** —— 能做加密就做。
 *
 * <p>密文格式：{@code base64(IV || ciphertext || tag)}。IV 每次随机、跟着密文一起存，
 * 这是 GCM 的硬要求（同一密钥下 IV 复用会直接毁掉 GCM 的安全性）。
 */
@Slf4j
@Component
public class NotifyCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    /** GCM 推荐的 IV 长度。12 字节是性能与安全的最佳点，别用 16。 */
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * **没配密钥不再拒绝启动。**
     *
     * <p>原先是「启用转发却没配密钥 → 抛异常、应用起不来」。那条设计有个说不通的
     * 前提：它要求启动时就判定「你会不会用转发」，而转发总开关现在在库里、
     * 随时可以打开。更糟的是它把「暂时不用转发」也一并拦住了 —— 一个可选能力
     * 不该成为整个应用的启动依赖。
     *
     * <p>现在分两种情况：
     * <ul>
     *   <li><b>没配</b> —— 照常启动，只是转发不可启用。使用者去界面上打开开关时
     *       会收到一条带操作指引的错误（见 {@link #requireReady()}）。</li>
     *   <li><b>配了但格式不对</b> —— 仍然拒绝启动。那是明确的配置错误：
     *       你既然填了，就是打算用它，而填错了不该被静默降级成「功能没反应」。</li>
     * </ul>
     */
    public NotifyCrypto(NotifyProperties properties) {
        String raw = properties.getEncryptKey();

        if (raw == null || raw.isBlank()) {
            this.key = null;
            log.info("""
                    未配置 app.notify.encrypt-key，消息转发不可启用。
                    要启用：在后端设 NOTIFY_ENCRYPT_KEY=$(openssl rand -base64 32) 后重启，
                    然后到管理后台「系统设置」里打开「消息转发」开关。""");
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.notify.encrypt-key 不是合法的 Base64。用 `openssl rand -base64 32` 生成一把。", e);
        }

        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(String.format(
                    "app.notify.encrypt-key 解码后是 %d 字节，需要 %d 字节（AES-256）。"
                            + "用 `openssl rand -base64 32` 生成一把。",
                    keyBytes.length, KEY_LENGTH_BYTES));
        }

        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 加密密钥是否就绪（即「这个后端能不能用转发」）。
     *
     * <p>给两处用：管理端在**进页面时**提示；以及「启用转发」那一下的校验 ——
     * 没有它的话，使用者会先填完整个渠道表单、点保存，才发现密钥根本没配，
     * 而那一刻填的东西全白填了。
     */
    public boolean isReady() {
        return key != null;
    }

    /** @return Base64 编码的密文（含 IV）。 */
    public String encrypt(String plaintext) {
        requireReady();
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 加密失败是配置/环境问题，不该被当成"某条投递失败"悄悄咽掉
            throw new IllegalStateException("渠道凭据加密失败", e);
        }
    }

    /**
     * @throws IllegalStateException 密文损坏或密钥不对。
     *
     * <p>注意它**不会**回退成「当明文用」：密钥换过之后老密文解不开，那时必须抛错、
     * 让人去重配渠道，而不是把一段乱码当成 webhook 地址去请求。
     */
    public String decrypt(String cipherText) {
        requireReady();
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "渠道凭据解密失败：密钥可能换过，或密文已损坏。需要重新配置该渠道。", e);
        }
    }

    /**
     * 密钥没配时**说清怎么办**，而不是只说「没配」。
     *
     * <p>这一条是踩出来的：原实现抛的是「消息转发未启用（app.notify.enabled=false）」，
     * 而它落进兜底的 Exception 处理器，到使用者眼前只剩一句「服务器内部错误」。
     * 现场看到的是「点保存报 500」，完全想不到是要去设环境变量。
     *
     * <p>把「该设哪个变量、怎么生成、要重启」全写进消息里 —— 这条消息本来就是
     * 给运维看的操作指引，短了反而没用。
     *
     * <p>公开是给「打开转发总开关」那一步用的：那一下必须先确认密钥在，
     * 否则会先报「启用成功」，等第一次配渠道时才失败 —— 而使用者那时已经
     * 以为功能打开了。
     */
    public void requireReady() {
        if (key != null) {
            return;
        }

        throw new NotifyNotConfiguredException(
                "消息转发功能未配置：后端没有设置加密密钥。\n"
                        + "在后端设置 NOTIFY_ENCRYPT_KEY=$(openssl rand -base64 32) 然后重启。\n"
                        + "docker 部署改 docker/.env，再 docker compose up -d backend。\n"
                        + "（渠道凭据是加密存储的，没有这把密钥就存不了。）\n"
                        + "注意：密钥丢了的话，已配的渠道凭据都要重新填 —— 密文在库里，没有密钥读不出来。");
    }
}
