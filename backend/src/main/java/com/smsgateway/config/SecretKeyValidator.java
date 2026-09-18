package com.smsgateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时检查主密钥是否还是仓库里的开发默认值。
 *
 * <p>加这个检查的理由很具体：{@code docker/docker-compose.yml} 原先只注入了数据源和
 * Redis 的环境变量，**没有**注入 {@code APP_SECRET_KEY} —— 照仓库文档部署出来的系统，
 * 用的就是写死在 application.yml 里的那个密钥，而它随仓库一起公开。
 *
 * <p>这个密钥等于系统主密钥：设备令牌是 {@code HMAC-SHA256(deviceId, key)}，确定性推导，
 * 知道 key 就能对任意 deviceId 算出合法令牌，冒充任何设备上报短信、读取它的全部记录。
 * 光靠文档里写一句「生产请覆盖」挡不住这件事 —— 所以至少让它响一声。
 */
@Slf4j
@Component
public class SecretKeyValidator implements CommandLineRunner {

    /** 开发默认值里刻意留的标记，见 application.yml 的 app.secret.key。 */
    private static final String DEV_MARKER = "DEV-ONLY";

    @Value("${app.secret.key}")
    private String secretKey;

    @Override
    public void run(String... args) {
        if (secretKey == null || !secretKey.contains(DEV_MARKER)) {
            return;
        }

        log.warn("""

                ============================================================
                 app.secret.key 仍是开发默认值（含 "{}"），**不要用于生产**。

                 设备令牌 = HMAC-SHA256(deviceId, 该密钥)，确定性推导：
                 凡是知道这个密钥的人，都能对任意 deviceId 直接算出合法令牌，
                 冒充任何设备上报短信、并读走它的全部记录。

                 请设置环境变量 APP_SECRET_KEY 后重启（见 docker/.env.example）。
                 注意：更换密钥会让**所有已签发的设备令牌立即失效**，
                 每台设备都需要重新注册。
                ============================================================
                """, DEV_MARKER);
    }
}
