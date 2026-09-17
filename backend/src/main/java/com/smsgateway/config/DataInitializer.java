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

    @Override
    public void run(String... args) {
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
}
