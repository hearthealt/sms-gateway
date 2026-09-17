package com.smsgateway.service;

import com.smsgateway.model.dto.LoginRequest;
import com.smsgateway.model.dto.LoginResponse;
import com.smsgateway.model.entity.AdminUser;
import com.smsgateway.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    public static final String ADMIN_TOKEN_PREFIX = "sms:admin:token:";

    @Value("${app.admin.token-ttl-seconds:7200}")
    private long tokenTtlSeconds;

    /**
     * 校验账号密码并签发 token。账号或密码错误统一返回同一条错误信息，
     * 避免泄露"用户名是否存在"。
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        AdminUser user = adminUserRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                ADMIN_TOKEN_PREFIX + token, user.getUsername(), tokenTtlSeconds, TimeUnit.SECONDS);

        user.setLastLoginAt(LocalDateTime.now());
        adminUserRepository.save(user);

        log.info("Admin login success: username={}", user.getUsername());
        return new LoginResponse(token, user.getUsername(), user.getDisplayName());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(ADMIN_TOKEN_PREFIX + token);
        }
    }

    /** token 有效则返回对应用户名，否则返回 null。 */
    public String resolveUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(ADMIN_TOKEN_PREFIX + token);
    }
}
