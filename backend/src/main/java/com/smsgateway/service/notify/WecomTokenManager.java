package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsgateway.service.notify.NotifyHttpClient.NotifyHttpResponse;
import com.smsgateway.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信应用消息的 access_token 管理。
 *
 * <p>官方对这块有明确要求，逐条落实：
 * <ul>
 *   <li><b>缓存 token</b>，不能频繁调 gettoken，否则会被频率拦截</li>
 *   <li><b>按应用隔离存储</b> —— 企微每个应用的 token 彼此独立</li>
 *   <li><b>提前刷新</b>，不要等到 7200 秒到期 —— 这里提前 5 分钟</li>
 *   <li><b>缓存击穿保护</b>：并发时只放一个线程去换</li>
 *   <li>收到 {@code 42001}（token 过期）时强刷重试一次 —— 官方明确「企业微信可能会
 *       出于运营需要**提前使 access_token 失效**」，所以到期时间不可信</li>
 * </ul>
 *
 * <p><b>已知限制：击穿保护是进程内的。</b>多实例部署时每个实例各自持有本地锁，
 * 仍可能同时去换 —— 而企微换 token 是**互斥**的，新 token 会让旧 token 立即失效，
 * 于是两个实例会互相把对方的 token 打掉，表现为「偶发地反复 42001」。
 * 真要跑多实例，这里得换成 Redis 分布式锁。当前项目是单实例（docker-compose 一套），
 * 所以没有引入那份复杂度 —— 但换部署形态时这是第一个要看的地方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomTokenManager {

    private static final String KEY_PREFIX = "sms:notify:wecom:token:";
    private static final String GET_TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

    /** 提前刷新的秒数。官方要求「不要等到到期」，5 分钟足够覆盖时钟漂移与几次失败的投递。 */
    private static final int REFRESH_AHEAD_SECONDS = 300;

    /** token 换不到时的兜底缓存时长，避免每次投递都去撞一次失败的 gettoken。 */
    private static final int MIN_CACHE_SECONDS = 60;

    private final NotifyHttpClient http;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 每个应用一把本地锁。key 是下面那个缓存 key。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 取一个可用 token，优先走缓存。
     *
     * @throws MissingConfigException 企业 ID / 密钥不对（重试无意义，要人去改配置）
     */
    public String getToken(String corpId, String corpSecret) {
        String key = cacheKey(corpId, corpSecret);

        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        synchronized (lockFor(key)) {
            // 双检：等锁的时候别的线程可能已经换好了
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }

            TokenResult result = fetchToken(corpId, corpSecret);
            redisTemplate.opsForValue()
                    .set(key, result.token(), result.ttlSeconds(), TimeUnit.SECONDS);
            return result.token();
        }
    }

    /**
     * 丢掉缓存，下次取时重新换。
     *
     * <p>给 {@code 42001} 用 —— 那个错误码的含义就是「你手上这个 token 已经不作数了」，
     * 不清缓存的话会拿着同一个废 token 一直重试到重试次数耗尽。
     */
    public void evict(String corpId, String corpSecret) {
        redisTemplate.delete(cacheKey(corpId, corpSecret));
    }

    private TokenResult fetchToken(String corpId, String corpSecret) {
        String url = GET_TOKEN_URL
                + "?corpid=" + URLEncoder.encode(corpId, StandardCharsets.UTF_8)
                + "&corpsecret=" + URLEncoder.encode(corpSecret, StandardCharsets.UTF_8);

        NotifyHttpResponse response = http.get(url);
        if (!response.hasResponse()) {
            throw new IllegalStateException("获取企业微信 access_token 失败：" + response.error());
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            int errcode = node.path("errcode").asInt(-1);

            if (errcode == 0) {
                String token = node.path("access_token").asText(null);
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException("企业微信返回了空的 access_token");
                }
                int expiresIn = node.path("expires_in").asInt(7200);
                return new TokenResult(
                        token,
                        Math.max(MIN_CACHE_SECONDS, expiresIn - REFRESH_AHEAD_SECONDS));
            }

            // 40001 corpsecret 不对 / 40013 corpid 不对 —— 配置错，重试没有意义
            if (errcode == 40001 || errcode == 40013) {
                throw new MissingConfigException(
                        "企业微信拒绝了这组凭证（errcode=" + errcode + "）：请检查企业 ID 与应用密钥。"
                                + "注意用的是**自建应用的 Secret**，不是通讯录同步的 Secret。");
            }

            String errmsg = NotifyRedactor.forStorage(node.path("errmsg").asText(""), 200);
            throw new IllegalStateException(
                    "获取企业微信 access_token 失败：errcode=" + errcode + " " + errmsg);
        } catch (MissingConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析企业微信 gettoken 响应失败：" + NotifyRedactor.truncate(response.body(), 200), e);
        }
    }

    /**
     * 缓存 key。
     *
     * <p>密钥**取哈希**而不是原样拼进去：Redis 的 key 在 {@code MONITOR}、
     * {@code SLOWLOG}、以及各种运维界面里都是明文可见的，把 corpSecret 写进 key
     * 等于把它散到了数据库之外的地方。
     */
    private String cacheKey(String corpId, String corpSecret) {
        return KEY_PREFIX + corpId + ":" + HashUtil.sha256(corpSecret).substring(0, 16);
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private record TokenResult(String token, int ttlSeconds) {
    }
}
