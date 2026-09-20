package com.smsgateway.service;

import com.smsgateway.model.dto.LoginRequest;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.model.dto.LoginResponse;
import com.smsgateway.model.entity.AdminUser;
import com.smsgateway.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final SysConfigService sysConfigService;

    public static final String ADMIN_TOKEN_PREFIX = "sms:admin:token:";

    /**
     * 登录有效期。从库里读，管理后台「系统设置」页可改，**改完立即生效**。
     *
     * <p>已经在登录状态的会话不受影响：它们的过期时间在签发时就写进 Redis 的 TTL 了，
     * 新时长只对之后登录的人生效。这一点写在了那项配置的说明里 —— 否则改完发现
     * 「当前这个会话没变」会以为是没生效。
     */
    private long tokenTtlSeconds() {
        return sysConfigService.getInt(SysConfigKey.ADMIN_TOKEN_TTL_SECONDS);
    }

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
                ADMIN_TOKEN_PREFIX + token, user.getUsername(), tokenTtlSeconds(), TimeUnit.SECONDS);

        user.setLastLoginAt(LocalDateTime.now());
        adminUserRepository.save(user);

        log.info("Admin login success: username={}", user.getUsername());
        // 把 TTL 一并回给前端，它才能自己判断令牌是不是过期了
        return new LoginResponse(token, user.getUsername(), user.getDisplayName(), tokenTtlSeconds());
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
