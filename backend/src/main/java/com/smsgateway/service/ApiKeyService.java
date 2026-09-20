package com.smsgateway.service;

import com.smsgateway.model.dto.ApiKeyRequest;
import com.smsgateway.model.dto.ApiKeyView;
import com.smsgateway.model.entity.ApiKey;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 外部调用方密钥。管理后台用：签发 / 列表 / 启停 / 删除；
 * {@code ClientAuthInterceptor} 用 {@link #validate(String)} 做鉴权。
 *
 * <p>校验走 Redis 缓存：命中就完全不碰数据库，未命中才查库并顺手刷新
 * {@code last_used_at} —— 于是「最后使用时间」每 TTL 最多写一次，
 * 而不是每个请求写一次库。
 *
 * <p>本类不得引用 {@code HttpServletRequest} 等 Web 层类型（只收原始 token 字符串），
 * 否则拦截器注入它会形成循环依赖。写法参照 {@link AdminAuthService#resolveUsername(String)}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final StringRedisTemplate redisTemplate;
    private final SysConfigService sysConfigService;

    /** 缓存键前缀。参照 AdminAuthService.ADMIN_TOKEN_PREFIX，常量放在服务类上。 */
    public static final String API_KEY_CACHE_PREFIX = "sms:apikey:";

    /** 密钥前缀，便于在日志/截图里一眼认出这是本系统的密钥。 */
    private static final String KEY_PREFIX = "sk-";

    /**
     * 校验结果的缓存时长。从库里读，管理后台「系统设置」页可改。
     *
     * <p>它是一个**取舍**：改大减轻数据库压力，但禁用/删除一个密钥后要过这么久才失效。
     * 主动禁用/删除时会调 {@code evictCache} 立刻清掉，所以这里的延迟只影响
     * 「密钥自然过期」与「数据库被直接改动」两种情形。
     */
    private long cacheTtlSeconds() {
        return sysConfigService.getInt(SysConfigKey.API_KEY_CACHE_TTL_SECONDS);
    }

    public List<ApiKeyView> list() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(ApiKeyService::toView)
                .toList();
    }

    @Transactional
    public ApiKeyView create(ApiKeyRequest request) {
        ApiKey entity = new ApiKey();
        entity.setName(request.getName());
        entity.setApiKey(generateKey());
        entity.setEnabled(true);
        entity.setExpiresAt(request.getExpiresAt());
        apiKeyRepository.save(entity);
        // 只记 id 和用途，密钥明文不进日志
        log.info("API key issued: id={}, name={}", entity.getId(), entity.getName());
        return toView(entity);
    }

    @Transactional
    public ApiKeyView setEnabled(Long id, boolean enabled) {
        ApiKey entity = find(id);
        entity.setEnabled(enabled);
        apiKeyRepository.save(entity);
        evictCache(entity.getApiKey());
        log.info("API key {} {}", id, enabled ? "enabled" : "disabled");
        return toView(entity);
    }

    @Transactional
    public void delete(Long id) {
        ApiKey entity = find(id);
        apiKeyRepository.delete(entity);
        evictCache(entity.getApiKey());
        log.info("API key deleted: id={}", id);
    }

    /**
     * 校验调用方传来的密钥，有效则返回该密钥的用途备注名，否则返回 null。
     *
     * <p>无效结果**不缓存**：否则刚签发的密钥要等一个 TTL 才能用。
     * 反过来，启停和删除必须走 {@link #evictCache(String)} 主动失效，
     * 不然禁用后要等 TTL 过期才真正挡住 —— 这个项目里原本没有任何写入侧失效逻辑，
     * 是新引入的约定，容易漏。
     */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String cacheKey = API_KEY_CACHE_PREFIX + token;
        String cachedName = redisTemplate.opsForValue().get(cacheKey);
        if (cachedName != null) {
            return cachedName;
        }

        ApiKey entity = apiKeyRepository.findByApiKey(token).orElse(null);
        if (entity == null || !entity.isEnabled() || entity.isExpired(LocalDateTime.now())) {
            return null;
        }

        entity.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(entity);
        redisTemplate.opsForValue().set(cacheKey, entity.getName(), cacheTtlSeconds(), TimeUnit.SECONDS);
        return entity.getName();
    }

    private void evictCache(String apiKey) {
        redisTemplate.delete(API_KEY_CACHE_PREFIX + apiKey);
    }

    /** sk- + 32 位十六进制，共 35 字符。 */
    private static String generateKey() {
        return KEY_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private ApiKey find(Long id) {
        return apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("密钥不存在: " + id));
    }

    private static ApiKeyView toView(ApiKey entity) {
        return new ApiKeyView(
                entity.getId(),
                entity.getName(),
                entity.getApiKey(),
                entity.isEnabled(),
                entity.getExpiresAt(),
                entity.getLastUsedAt(),
                entity.getCreatedAt());
    }
}
