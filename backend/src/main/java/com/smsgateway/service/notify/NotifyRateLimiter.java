package com.smsgateway.service.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每个渠道一个令牌桶，用 Redis 实现（多实例部署下也安全）。
 *
 * <p>为什么不能图省事用「本地 Guava RateLimiter」：那样每个实例各限各的，
 * 两实例部署时实际速率翻倍。而对端的限额是按**渠道**算的、不是按实例算的 ——
 * 超了就是超了，罚的是渠道凭证。
 *
 * <p>为什么取不到令牌要**推迟而不是失败**：限流是预期内的事（20 条/分钟的渠道
 * 收到 21 条短信完全正常），把它记成一次失败会让「连续失败 10 次自动停用渠道」
 * 在一个繁忙的下午把好端端的渠道给停了。推迟不消耗重试次数，见
 * {@code NotifyDispatcher}。
 *
 * <p>用 Lua 保证「读桶 → 补令牌 → 扣一个 → 写回」这一串是原子的。
 * 分成几条命令写的话，两个实例同时读到「还剩 1 个」就会都放行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyRateLimiter {

    private static final String KEY_PREFIX = "sms:notify:rl:";

    /**
     * @return 0 表示拿到令牌；正数表示需要等待的**毫秒**（桶空了）；
     *         -1 表示限额未启用（不限速）
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerSecond = tonumber(ARGV[2])
            local nowMs = tonumber(ARGV[3])
            local ttlSeconds = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = nowMs
            end

            local elapsed = nowMs - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refillPerSecond)

            local waitMs = 0
            if tokens >= 1 then
              tokens = tokens - 1
            else
              waitMs = math.ceil((1 - tokens) / refillPerSecond * 1000)
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', nowMs)
            redis.call('EXPIRE', key, ttlSeconds)
            return waitMs
            """;

    private final StringRedisTemplate redisTemplate;

    private final RedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);

    /**
     * 尝试取一个令牌。
     *
     * @param limitPerMin 每分钟上限；{@code <= 0} 表示不限速，直接放行
     * @return 0 = 已取到；> 0 = 需要等待这么多毫秒；-1 = 不限速
     */
    public long tryAcquire(Long channelId, int limitPerMin) {
        if (limitPerMin <= 0) {
            return -1L;
        }

        // 桶容量 = 一分钟的量，补充速率 = 每分钟量 / 60 秒。
        // 容量取一分钟而不是 1：突发几条一起到达是常态（同一秒收到多条短信），
        // 桶只装 1 个令牌的话每条都要等一个补充周期，明明没超限却慢得离谱。
        double refillPerSecond = limitPerMin / 60.0;
        // TTL 取两分钟：桶空置久了自然过期，不必手动清理；
        // 而两分钟足够长，不会在正常使用中把计数重置掉。
        long ttlSeconds = 120;

        try {
            Long waitMs = redisTemplate.execute(
                    script,
                    List.of(KEY_PREFIX + channelId),
                    String.valueOf(limitPerMin),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(ttlSeconds));

            return waitMs == null ? 0L : Math.max(0L, waitMs);
        } catch (Exception e) {
            // Redis 挂了不该让转发整个停摆 —— 放行并按不限速处理。
            // 限流是保护对端的，不是保护我们自己的；宁可冒一次超限的风险，
            // 也不要因为缓存故障把短信全憋在库里。
            log.warn("限流检查失败，本次放行（按不限速处理）：channelId={}", channelId, e);
            return -1L;
        }
    }
}
