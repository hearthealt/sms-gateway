package com.smsgateway.service;

import com.smsgateway.model.dto.SmsWaitResponse;
import com.smsgateway.util.PhoneUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 等待短信验证码。
 *
 * <p>匹配维度只有**归一化后的接收号码**，不看发送方 —— 外部调用方只关心
 * 「这个号收到了什么验证码」，不该需要知道是谁发的。号码归一化见 {@link PhoneUtil}，
 * 写入方 SmsService 用的是同一套规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingService implements MessageListener {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CompletableFuture<SmsWaitResponse>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sms-wait-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean listening = new AtomicBoolean(false);

    private static final String SMS_WAIT_KEY_PREFIX = "sms:wait:";
    private static final String SMS_CODE_KEY_PREFIX = "sms:code:";
    private static final String SMS_CHANNEL_PATTERN = "sms:channel:*";
    private static final long POLL_INTERVAL_MS = 500;

    /**
     * 单次等待的秒数上限，同时也是对外的公开上限（控制器用它给 future.get 定界）。
     *
     * <p>没有上限时，一个持有合法 API Key 的调用方并发发 200 个
     * {@code timeout=86400} 就能把 Tomcat 默认的 200 个工作线程全挂满 24 小时，
     * 此后设备注册、心跳、上报、管理后台全部无响应 —— 整个网关瘫掉。
     */
    public static final long MAX_WAIT_SECONDS = 120;

    /** 下限 1 秒：非正数会让下面 Redis 的 TTL 变成 0 或负数，直接报错（而不是返回 400）。 */
    private static final long MIN_WAIT_SECONDS = 1;

    @PostConstruct
    public void init() {
        redisMessageListenerContainer.addMessageListener(this, new PatternTopic(SMS_CHANNEL_PATTERN));
        redisMessageListenerContainer.start();
        listening.set(true);
        log.info("WaitingService initialized, subscribed to pattern: {}", SMS_CHANNEL_PATTERN);
    }

    @PreDestroy
    public void destroy() {
        listening.set(false);
        redisMessageListenerContainer.removeMessageListener(this);
        scheduler.shutdown();
        log.info("WaitingService destroyed");
    }

    /**
     * 等待指定号码收到的验证码。先查 Redis 缓存（短信先到、后调接口的情况），
     * 命中立即返回，否则挂起等待直到超时。
     *
     * @param phone          接收号码，允许带 +86 / 空格 / 横线，内部会归一化
     * @param timeoutSeconds 等待秒数
     */
    public CompletableFuture<SmsWaitResponse> waitForSms(String phone, long timeoutSeconds) {
        // 在服务层夹紧而不是在控制器：这样任何调用方都绕不过去。
        // 上限防的是「拿合法凭据占满 Tomcat 线程」；下限防的是非正数让 Redis 的
        // TTL 变成 0 或负数、抛错被兜成 500（本该是一次正常的等待）。
        long effectiveTimeout = Math.max(MIN_WAIT_SECONDS, Math.min(timeoutSeconds, MAX_WAIT_SECONDS));
        if (effectiveTimeout != timeoutSeconds) {
            log.info("Wait timeout {}s clamped to {}s", timeoutSeconds, effectiveTimeout);
        }

        String normalizedPhone = PhoneUtil.normalize(phone);

        // 归一化后为空说明压根没法匹配。这里必须报参数错（400）而不是超时（408）——
        // 报超时会让调用方去查「为什么没等到短信」，方向完全错了。
        if (normalizedPhone.isEmpty()) {
            throw new IllegalArgumentException("手机号格式不正确: " + phone);
        }

        // First check Redis for existing code
        String codeKey = SMS_CODE_KEY_PREFIX + normalizedPhone;
        String existingCode = redisTemplate.opsForValue().get(codeKey);
        if (existingCode != null && !existingCode.isEmpty()) {
            // 缓存里只存了验证码本身，拿不到发送方和正文，这两个字段只能是 null
            SmsWaitResponse response = new SmsWaitResponse(existingCode, null, null, normalizedPhone, null);
            return CompletableFuture.completedFuture(response);
        }

        // Create a pending future
        CompletableFuture<SmsWaitResponse> future = new CompletableFuture<>();
        pendingRequests.put(normalizedPhone, future);

        // Store wait marker in Redis with TTL = timeout + 10s buffer
        String waitRedisKey = SMS_WAIT_KEY_PREFIX + normalizedPhone;
        redisTemplate.opsForValue().set(waitRedisKey, "waiting", effectiveTimeout + 10, TimeUnit.SECONDS);

        startPolling(normalizedPhone, waitRedisKey, future);

        // Schedule timeout
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("SMS wait timed out after " + effectiveTimeout + " seconds"));
                pendingRequests.remove(normalizedPhone);
                redisTemplate.delete(waitRedisKey);
            }
        }, effectiveTimeout, TimeUnit.SECONDS);

        return future;
    }

    private void startPolling(String phone, String waitRedisKey, CompletableFuture<SmsWaitResponse> future) {
        String codeKey = SMS_CODE_KEY_PREFIX + phone;

        ScheduledFuture<?> poller = scheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                return;
            }

            String code = redisTemplate.opsForValue().get(codeKey);
            if (code != null && !code.isEmpty()) {
                SmsWaitResponse response = new SmsWaitResponse(code, null, null, phone, null);
                future.complete(response);
                pendingRequests.remove(phone);
                return;
            }

            // Check if the waiting key still exists (not expired/cleaned)
            String waitStatus = redisTemplate.opsForValue().get(waitRedisKey);
            if (waitStatus == null) {
                if (!future.isDone()) {
                    future.completeExceptionally(new TimeoutException("SMS wait timed out"));
                    pendingRequests.remove(phone);
                }
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 必须显式撤销这个周期任务。
        //
        // 回调里那句 `if (future.isDone()) return;` 只是让这一轮提前返回 —— 任务本身
        // 仍留在调度队列里，每 500ms 被唤醒一次，并且一直持有 future、闭包和
        // Redis key 字符串不放，GC 也回收不掉。原先返回的句柄没人接、全项目没有
        // 一处 cancel，于是**每次 wait 调用都泄漏一个永久的周期任务**：
        // 按每天一万次算，一天后单线程调度器每秒要空转两万个 runnable，把
        // 新请求的轮询与超时回调饿死，堆也持续增长。
        //
        // 挂 whenComplete 而不是在每个完成分支里各写一遍：正常出码、超时、
        // 异常三条路径都能覆盖到，以后加分支也不会漏。
        future.whenComplete((result, error) -> poller.cancel(false));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(body, Map.class);
            String sender = (String) data.get("sender");
            // 归一化后再匹配。发布方已经归一化过一次，这里再走一遍是幂等的，
            // 也防住将来有别的发布方直接用设备上报的原始号码（带 +86）。
            String phone = PhoneUtil.normalize((String) data.get("phone"));
            String code = (String) data.get("code");

            if (!phone.isEmpty()) {
                CompletableFuture<SmsWaitResponse> future = pendingRequests.get(phone);
                if (future != null && !future.isDone()) {
                    Long receivedAt = data.get("receivedAt") != null ? ((Number) data.get("receivedAt")).longValue() : null;
                    String content = (String) data.get("content");
                    SmsWaitResponse response = new SmsWaitResponse(code, sender, content, phone, receivedAt);
                    future.complete(response);
                    pendingRequests.remove(phone);
                    log.debug("Waiting request fulfilled via pub/sub: phone={}, sender={}", phone, sender);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process Redis pub/sub message on channel: {}", channel, e);
        }
    }
}
