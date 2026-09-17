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
        redisTemplate.opsForValue().set(waitRedisKey, "waiting", timeoutSeconds + 10, TimeUnit.SECONDS);

        startPolling(normalizedPhone, waitRedisKey, future);

        // Schedule timeout
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("SMS wait timed out after " + timeoutSeconds + " seconds"));
                pendingRequests.remove(normalizedPhone);
                redisTemplate.delete(waitRedisKey);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        return future;
    }

    private void startPolling(String phone, String waitRedisKey, CompletableFuture<SmsWaitResponse> future) {
        String codeKey = SMS_CODE_KEY_PREFIX + phone;

        scheduler.scheduleAtFixedRate(() -> {
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
