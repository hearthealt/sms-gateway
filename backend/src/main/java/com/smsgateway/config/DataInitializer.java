package com.smsgateway.config;

import com.smsgateway.model.entity.AdminUser;
import com.smsgateway.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建管理员账号。
 * 密码哈希不写死在 schema.sql 里，而是启动时现算，便于通过配置修改默认密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AdminUserRepository adminUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${app.admin.default-username:admin}")
    private String defaultUsername;

    @Value("${app.admin.default-password:admin123}")
    private String defaultPassword;

    @Value("${app.admin.reset-password:}")
    private String resetPassword;

    @Override
    public void run(String... args) {
        // 轮换优先于创建：设了这个变量就说明意图是「换掉现有密码」。
        if (resetPassword != null && !resetPassword.isBlank()) {
            rotatePassword();
            return;
        }

        if (adminUserRepository.count() > 0) {
            return;
        }

        AdminUser user = new AdminUser();
        user.setUsername(defaultUsername);
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));
        user.setDisplayName(defaultUsername);
        user.setStatus("ACTIVE");
        adminUserRepository.save(user);

        log.warn("Created default admin account '{}'. 请尽快修改默认密码（app.admin.default-password）。",
                defaultUsername);
    }

    /**
     * 重置管理员密码。
     *
     * <p>这是**唯一**能改密码的途径。原先 {@code default-password} 只在 admin_user 表
     * 为空时生效，而管理接口里也没有改密码的功能 —— 于是账号一旦建过，仓库日志里那句
     * 「请尽快修改默认密码」根本无从执行，默认口令会一直跟着这套系统。
     *
     * <p>设 {@code APP_ADMIN_RESET_PASSWORD} 重启即完成轮换，之后把该环境变量撤掉，
     * 否则每次启动都会把密码改回它。
     */
    private void rotatePassword() {
        adminUserRepository.findByUsername(defaultUsername).ifPresentOrElse(
                user -> {
                    user.setPasswordHash(passwordEncoder.encode(resetPassword));
                    adminUserRepository.save(user);
                    log.warn("Admin password for '{}' has been RESET via APP_ADMIN_RESET_PASSWORD. "
                            + "请立即撤掉该环境变量，否则每次启动都会重置成同一个值。", defaultUsername);
                },
                () -> log.warn("APP_ADMIN_RESET_PASSWORD 已设置，但不存在管理员 '{}'，什么都没做。",
                        defaultUsername)
        );
    }
}
