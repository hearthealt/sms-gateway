package com.smsgateway.service.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsgateway.config.NotifyProperties;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.NotifyDeliveryStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.SmsMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 投递调度器：轮询 outbox，把到期的记录发出去。
 *
 * <p>几条关键设计：
 *
 * <p><b>同一渠道串行、不同渠道并行。</b>调度器按 channel 分组，每组交给线程池执行，
 * 但用 {@code inFlightChannels} 挡住同一渠道的并发批次 —— 对端的限额是按渠道算的，
 * 并发发送会在一瞬间打满限额（钉钉超限罚 10 分钟、Slack 超限可能永久禁用）。
 *
 * <p><b>限流不消耗重试次数。</b>取不到令牌只把 {@code next_retry_at} 往后推。
 * 把它算成一次失败，会让「连续失败 10 次自动停用渠道」在一个繁忙的下午
 * 把好端端的渠道停掉 —— 而它其实一条都没发失败过。
 *
 * <p><b>Full Jitter 退避。</b>纯指数退避会让重试「成簇」同时到达，反而加剧限流；
 * AWS 的实测结论是加抖动能把总调用次数减少一半以上。
 */
@Slf4j
@Service
public class NotifyDispatcher {

    private static final int ERROR_COLUMN_LENGTH = 500;

    private final NotifyProperties properties;
    private final SysConfigService sysConfigService;
    private final NotifyDeliveryRepository deliveryRepository;
    private final NotifyChannelRepository channelRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final DeviceRepository deviceRepository;
    private final ChannelSenderRegistry senderRegistry;
    private final NotifyCrypto crypto;
    private final NotifyMessageFactory messageFactory;
    private final NotifyRateLimiter rateLimiter;
    private final NotifyErrorClassifier errorClassifier;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    /**
     * 正在发送中的渠道。挡的是「同一渠道同时跑两批」—— 见类注释。
     * 用 Set 而不是给每个渠道一把锁：只在调度线程里 add，发送线程里 remove。
     */
    private final Set<Long> inFlightChannels = ConcurrentHashMap.newKeySet();

    /**
     * 上一轮真正跑的时刻。
     *
     * <p>调度器固定每秒唤醒（周期在启动就定死了），而「间隔多久跑一轮」是运行期配置 ——
     * 所以用它来判断这一秒该不该干活。见 {@link #dispatch()}。
     */
    private volatile long lastDispatchAt = 0L;

    public NotifyDispatcher(NotifyProperties properties,
                            SysConfigService sysConfigService,
                            NotifyDeliveryRepository deliveryRepository,
                            NotifyChannelRepository channelRepository,
                            SmsMessageRepository smsMessageRepository,
                            DeviceRepository deviceRepository,
                            ChannelSenderRegistry senderRegistry,
                            NotifyCrypto crypto,
                            NotifyMessageFactory messageFactory,
                            NotifyRateLimiter rateLimiter,
                            NotifyErrorClassifier errorClassifier,
                            ObjectMapper objectMapper,
                            @Qualifier("notifyExecutor") Executor executor) {
        this.properties = properties;
        this.sysConfigService = sysConfigService;
        this.deliveryRepository = deliveryRepository;
        this.channelRepository = channelRepository;
        this.smsMessageRepository = smsMessageRepository;
        this.deviceRepository = deviceRepository;
        this.senderRegistry = senderRegistry;
        this.crypto = crypto;
        this.messageFactory = messageFactory;
        this.rateLimiter = rateLimiter;
        this.errorClassifier = errorClassifier;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 调度轮询。
     *
     * <p><b>固定每秒唤醒一次，轮询间隔由配置决定要不要真干活。</b>轮询间隔现在是
     * 运行期可调的（{@code sys_config}），而 {@code @Scheduled(fixedDelayString=...)}
     * 的周期在启动就定死了 —— 想让它跟着配置走只能用 {@code SchedulingConfigurer}
     * 重建 trigger，为这点收益不值当。空转一次的代价是几纳秒，可以忽略。
     */
    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (!enabled()) {
            return;
        }

        long intervalMs = pollIntervalMs();
        long now = System.currentTimeMillis();
        if (now - lastDispatchAt < intervalMs) {
            return;
        }
        // 先记下时刻再干活：这一轮即使没捞到任务也算「跑过了」，
        // 否则空库时会在每个调度周期都查一次
        lastDispatchAt = now;

        try {
            List<NotifyDelivery> due = deliveryRepository.findDue(
                    NotifyDeliveryStatus.PENDING,
                    LocalDateTime.now(),
                    PageRequest.of(0, properties.getDispatcher().getBatchSize()));

            if (due.isEmpty()) {
                return;
            }

            for (Map.Entry<Long, List<NotifyDelivery>> entry : groupByChannel(due).entrySet()) {
                Long channelId = entry.getKey();
                List<NotifyDelivery> batch = entry.getValue();

                // 该渠道上一批还没跑完：这一批留到下轮（记录仍是 PENDING，不会丢）
                if (!inFlightChannels.add(channelId)) {
                    continue;
                }

                executor.execute(() -> {
                    try {
                        sendBatch(channelId, batch);
                    } catch (Exception e) {
                        // 兜到最外层：一批里某条出了意外，不能让整批任务连同线程一起没了，
                        // 更不能让 inFlightChannels 永远留着这个 id（那样该渠道就再也不会被调度）
                        log.error("投递批次异常：channelId={}", channelId, e);
                    } finally {
                        inFlightChannels.remove(channelId);
                    }
                });
            }
        } catch (Exception e) {
            log.error("投递调度轮询异常", e);
        }
    }

    /**
     * 回收卡在 SENDING 的记录。
     *
     * <p>进程在「已标记 SENDING、还没发完」之间被杀时，记录会永远停在 SENDING，
     * 而调度器只捞 PENDING —— 那条短信**静默地再也不发了**，管理端还看不出
     * 它和「正在发」有什么区别。
     *
     * <p>单独一个更慢的周期，不跟主轮询共用：主轮询默认 1 秒一次，
     * 每秒钟查一遍「有没有卡死的」纯属浪费。
     */
    @Scheduled(fixedDelay = 60_000)
    public void recoverStuck() {
        if (!enabled()) {
            return;
        }

        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(properties.getStuckSendingSeconds());
            List<NotifyDelivery> stuck = deliveryRepository.findStuck(
                    NotifyDeliveryStatus.SENDING, cutoff, PageRequest.of(0, 100));

            for (NotifyDelivery delivery : stuck) {
                delivery.setStatus(NotifyDeliveryStatus.PENDING);
                delivery.setNextRetryAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
            }

            if (!stuck.isEmpty()) {
                log.warn("回收了 {} 条卡在 SENDING 的投递记录（进程可能中途被杀过）", stuck.size());
            }
        } catch (Exception e) {
            log.error("回收卡死记录失败", e);
        }
    }

    /**
     * 清扫停用渠道下还没发出去的记录 —— 把它们置为「已取消」。
     *
     * <p>为什么需要这条兜底：**停用渠道有两个入口**，管理员点停用（那条路径会当场清），
     * 以及渠道连续失败到阈值**自动停用**（见 {@link #disableChannel}，它不经过前者）。
     * 少了这条，自动停用的渠道会把积压一直挂着 —— 管理端显示成「待投递 + 下次重试 13:51」，
     * 一个停在过去的时间，读起来像马上要发出去，实际永远不会。
     *
     * <p>与 {@link #recoverStuck} 错开成两个定时任务：那个管「卡在 SENDING 的」，
     * 这个管「渠道停了的」，两件事的日志与阈值都不同，合成一个会让名字和内容对不上。
     *
     * <p>**刻意不判总开关**（{@link #recoverStuck} 判了）：那条是「把卡住的重新排进队列」，
     * 总开关关着时排进去也没人捞，判掉合理；而这条是「清掉永远发不出去的东西」，
     * 无论转发开不开都该清 —— 何况总开关关着的时候，那些记录更不可能自己消失。
     */
    @Scheduled(fixedDelay = 60_000)
    public void cancelDeliveriesForDisabledChannels() {
        try {
            int cancelled = deliveryRepository.cancelUnsentForDisabledChannels(
                    "渠道已停用，本条不再投递");
            if (cancelled > 0) {
                log.info("清扫了 {} 条所属渠道已停用的未投递记录", cancelled);
            }
        } catch (Exception e) {
            log.error("清扫停用渠道的投递记录失败", e);
        }
    }

    /** 总开关在库里，改完立即生效 —— 不必重启。 */
    private boolean enabled() {
        return sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED);
    }

    /**
     * 轮询间隔（毫秒）。
     *
     * <p>下限 100ms 是防呆：这个值可以被改成 0，那会让调度周期变成"能跑多快跑多快"，
     * 而它每一轮都要查一次库。上限 60s 同理 —— 填个超大的值等于转发停了，
     * 而界面上看不出有什么不对。
     */
    private long pollIntervalMs() {
        long configured = sysConfigService.getInt(SysConfigKey.NOTIFY_POLL_INTERVAL_MS);
        return Math.max(100, Math.min(60_000, configured));
    }

    private Map<Long, List<NotifyDelivery>> groupByChannel(List<NotifyDelivery> deliveries) {
        Map<Long, List<NotifyDelivery>> grouped = new LinkedHashMap<>();
        for (NotifyDelivery delivery : deliveries) {
            grouped.computeIfAbsent(delivery.getChannelId(), k -> new java.util.ArrayList<>())
                    .add(delivery);
        }
        return grouped;
    }

    private void sendBatch(Long channelId, List<NotifyDelivery> batch) {
        NotifyChannel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null) {
            // 渠道被删了。记录留在 PENDING 而不是判死：删渠道可能是个误操作，
            // 恢复之后这些短信还发得出去。真要清掉，管理端有删除投递记录的入口。
            log.debug("渠道 {} 已不存在，其投递记录保持待投递", channelId);
            return;
        }
        if (!channel.isEnabled()) {
            // 正常到不了这里：findDue 已经把停用渠道的记录滤掉了。
            // 留它是防「查出来之后、发送之前被停用」那个窗口。
            //
            // 停用的记录**保持待投递**，不判死也不删 —— 停用是「暂时别发」而不是
            // 「丢掉」。代价是重新启用时会把积压的一次性补发出去（那些验证码多半
            // 已经过期了），这一点在管理端的投递记录页会显示成「已暂停」。
            log.debug("渠道 {} 已停用，其投递记录保持待投递", channelId);
            return;
        }

        for (NotifyDelivery delivery : batch) {
            long waitMs = rateLimiter.tryAcquire(channelId, channel.getRateLimitPerMin());

            if (waitMs > 0) {
                // 桶空了。剩下的这一轮不用再试了 —— 再试还是空的，
                // 而且每试一次都要一次 Redis 往返。
                postpone(delivery, waitMs);
                break;
            }

            sendOne(channel, delivery);
        }
    }

    private void sendOne(NotifyChannel channel, NotifyDelivery delivery) {
        delivery.setStatus(NotifyDeliveryStatus.SENDING);
        delivery.setAttempts(delivery.getAttempts() + 1);
        deliveryRepository.save(delivery);

        SendResult result;
        try {
            SmsMessage sms = smsMessageRepository.findById(delivery.getSmsMessageId()).orElse(null);
            if (sms == null) {
                // 短信被删了（管理端删设备会连带删它的短信）。再重试也没有可发的内容。
                markDead(channel, delivery, "对应的短信记录已不存在");
                return;
            }

            ChannelSender sender = senderRegistry.get(channel.getType());
            if (sender == null) {
                markDead(channel, delivery, "没有该渠道类型的发送实现：" + channel.getType());
                return;
            }

            RenderedMessage message = messageFactory.build(sms, resolveDeviceName(sms));
            result = sender.send(ChannelConfig.of(decryptConfig(channel)), message);

        } catch (MissingConfigException | SsrfBlockedException e) {
            // 配置错 / SSRF 拦截：重试一百次也不会变对。**必须在这里判死**，
            // 掉进下面那个通用 catch 会被当成「没到达对端、可重试」而一直重试。
            markDead(channel, delivery, e.getMessage());
            return;
        } catch (Exception e) {
            // 没预料到的异常。归为可重试 —— 不确定就别放弃，重试几次的代价
            // 远小于静默丢一条验证码。
            log.warn("投递时出现未预期异常：channelId={}, deliveryId={}",
                    channel.getId(), delivery.getId(), e);
            result = SendResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        applyResult(channel, delivery, result);
    }

    /**
     * 把一次发送的结果落到记录与渠道上。
     *
     * <p>**包级可见是为了能被单测直接调** —— 它守的是一条会写脏数据的规矩
     * （成功时不得留失败原因），而那条规矩恰好曾经破过。走 {@code sendOne} 去测
     * 要连发送实现一起桩掉，绕远且测不到重点。
     */
    void applyResult(NotifyChannel channel, NotifyDelivery delivery, SendResult result) {
        delivery.setResponseCode(result.hasResponse() ? result.statusCode() : null);

        if (result.success()) {
            delivery.setStatus(NotifyDeliveryStatus.SUCCESS);
            delivery.setSentAt(LocalDateTime.now());
            // **成功后必须清掉上一轮留下的失败原因。**
            //
            // 这一条踩过：原先是先无条件写 lastError、再判成功，于是**每条成功记录都挂着
            // 一句「HTTP 200：{...}」**（describe() 只是把状态码和响应体拼起来，成功也一样拼），
            // 现场看到「状态是成功、却有一句报错」，以为转发一直在失败。
            //
            // 不清的话还有第二种情形更误导：第一次失败、重试成功 —— 那条真实错误已经不成立，
            // 留着会让这条记录看起来「成功了但仍然报错」。
            delivery.setLastError(null);
            deliveryRepository.save(delivery);

            channel.setLastSuccessAt(LocalDateTime.now());
            channel.setLastError(null);
            channel.setConsecutiveFailures(0);
            channelRepository.save(channel);
            return;
        }

        // ---- 以下都是失败路径，这时 describe() 才是一句失败原因 ----
        delivery.setLastError(NotifyRedactor.forStorage(result.describe(), ERROR_COLUMN_LENGTH));

        NotifyErrorClassifier.RetryDecision decision = errorClassifier.classify(result);

        switch (decision) {
            case TERMINAL_DISABLE_CHANNEL -> {
                markDead(channel, delivery, result.describe());
                disableChannel(channel,
                        "连续收到对端拒绝（" + NotifyRedactor.truncate(result.describe(), 200) + "），已自动停用");
            }

            case TERMINAL -> markDead(channel, delivery, result.describe());

            case RETRY -> {
                if (delivery.getAttempts() >= channel.getMaxRetries()) {
                    markDead(channel, delivery,
                            "重试 " + delivery.getAttempts() + " 次仍未成功：" + result.describe());
                    break;
                }
                scheduleRetry(channel, delivery, result);
            }
        }
    }

    private void scheduleRetry(NotifyChannel channel, NotifyDelivery delivery, SendResult result) {
        long delayMs = fullJitterBackoff(delivery.getAttempts());

        // 429 再额外多等一会儿：对端明确要求你慢下来，而我们本来就该给它留余地
        if (errorClassifier.isRateLimited(result)) {
            delayMs = Math.max(delayMs, 30_000L);
        }

        delivery.setStatus(NotifyDeliveryStatus.FAILED);
        delivery.setNextRetryAt(LocalDateTime.now().plusNanos(delayMs * 1_000_000L));
        deliveryRepository.save(delivery);

        registerFailure(channel, result.describe());

        log.info("投递失败将重试：deliveryId={}, attempts={}, 等待 {}ms",
                delivery.getId(), delivery.getAttempts(), delayMs);
    }

    /**
     * Full Jitter：随机取 {@code [0, min(cap, base * 2^attempts))}。
     *
     * <p>不加抖动的指数退避会让同时失败的一批消息在同一时刻一起重试，
     * 对端看到的是又一轮尖峰 —— 于是再次限流、再次成簇。AWS 的实测结论是
     * 「The no-jitter exponential backoff approach is the clear loser」。
     */
    private long fullJitterBackoff(int attempts) {
        long base = properties.getDispatcher().getRetryBaseMs();
        long cap = properties.getDispatcher().getRetryCapMs();
        // attempts 由 maxRetries 限制在个位数，1L << attempts 不会溢出
        long upperBound = Math.min(cap, base * (1L << Math.min(attempts, 20)));
        return ThreadLocalRandom.current().nextLong(Math.max(1, upperBound));
    }

    /** 限流取不到令牌：只推迟，**不消耗重试次数、不计失败**。 */
    private void postpone(NotifyDelivery delivery, long waitMs) {
        delivery.setNextRetryAt(LocalDateTime.now().plusNanos(waitMs * 1_000_000L));
        deliveryRepository.save(delivery);
    }

    private void markDead(NotifyChannel channel, NotifyDelivery delivery, String reason) {
        delivery.setStatus(NotifyDeliveryStatus.DEAD);
        delivery.setLastError(NotifyRedactor.forStorage(reason, ERROR_COLUMN_LENGTH));
        deliveryRepository.save(delivery);

        registerFailure(channel, reason);
        log.warn("投递放弃：channelId={}, deliveryId={}, 原因：{}",
                channel.getId(), delivery.getId(), NotifyRedactor.truncate(reason, 200));
    }

    private void registerFailure(NotifyChannel channel, String reason) {
        channel.setConsecutiveFailures(channel.getConsecutiveFailures() + 1);
        channel.setLastErrorAt(LocalDateTime.now());
        channel.setLastError(NotifyRedactor.forStorage(reason, ERROR_COLUMN_LENGTH));

        int threshold = sysConfigService.getInt(SysConfigKey.NOTIFY_FAILURE_THRESHOLD);
        if (channel.exceededFailureThreshold(threshold) && channel.isEnabled()) {
            disableChannel(channel, "连续失败 " + channel.getConsecutiveFailures()
                    + " 次已达阈值，自动停用。修好后请手动启用。");
            return;
        }
        channelRepository.save(channel);
    }

    private void disableChannel(NotifyChannel channel, String reason) {
        channel.setEnabled(false);
        channel.setLastError(NotifyRedactor.forStorage(reason, ERROR_COLUMN_LENGTH));
        channel.setLastErrorAt(LocalDateTime.now());
        channelRepository.save(channel);

        // 自动停用必须响一声，否则就是「渠道挂了没人知道」。
        // 用日志而不是往别的渠道发告警：一个挂掉的渠道不该尝试通过可能同样挂掉的
        // 转发通道去报警 —— 一个挂掉的渠道不该尝试通过可能同样挂掉的通道去报警。
        log.error("【转发渠道已自动停用】{}（id={}）：{}", channel.getName(), channel.getId(), reason);
    }

    private Map<String, Object> decryptConfig(NotifyChannel channel) {
        try {
            String json = crypto.decrypt(channel.getConfigCipher());
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new MissingConfigException("渠道配置解密失败：" + e.getMessage());
        }
    }

    private String resolveDeviceName(SmsMessage sms) {
        if (sms.getDeviceId() == null) {
            return "";
        }
        return deviceRepository.findById(sms.getDeviceId())
                .map(SmsDevice::getDeviceName)
                .orElse("");
    }
}
