package com.smsgateway.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 随机密钥生成。
 *
 * <p>抽出来是因为有不止一处要生成「抗爆破、又能人工转述」的随机串：设备恢复码
 * （{@code AdminDeviceService}）与设备接入口令（{@code DeviceEnrollTokenService}）。
 * 两处各写一遍的话，迟早有一处被改成 16 字节而没人发现 —— 那正是这类代码最容易
 * 悄悄降级的地方。
 */
public class SecretGenerator {

    /**
     * 复用同一个实例而不是每次 new：SecureRandom 的构造会去读系统熵源，
     * 在容器里首次初始化开销不小。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 32 字节随机 → base64url（43 字符）：足够抗爆破，也便于人工转述。 */
    public static String randomSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
