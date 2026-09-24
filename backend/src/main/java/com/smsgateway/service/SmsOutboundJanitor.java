package com.smsgateway.service;

import com.smsgateway.model.entity.SmsOutbound;
import com.smsgateway.repository.SmsOutboundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 外发短信的两个收尾：判「结果未知」与保留清理。
 *
 * <p><b>为什么要有「结果未知」这一档。</b>外发短信**绝不重发**（重发是真的又发一条
 * 出去、又计费一次），所以回执一丢就没有任何办法确认结果。那些记录会永远停在
 * 「已下发」—— 而「已下发」读起来像「在等回执」，实际上不会再有回执了。
 * 判成「结果未知」是这份状态机里最诚实的一格：既不能说成功（没证据），
 * 也不能说失败（可能已经发出去了），更不能重发。控制台照实说，让人自己去对账。
 *
 * <p>时间窗取 10 分钟：设备每 30 秒一次心跳，回执本来在下一秒就到了；给到 10 分钟
 * 是为了容忍「发出去之后 App 被强停、后来重启」这种要等下一次心跳的情形。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsOutboundJanitor {

    /** 一轮最多处理多少条。与其它清理任务一样分批，避免单个巨型事务。 */
    private static final int BATCH_SIZE = 200;

    /** 交给设备多久没回执就判成「结果未知」。 */
    private static final int UNKNOWN_AFTER_MINUTES = 10;

    /**
     * 保留期 90 天，硬编码。
     *
     * <p>比事件日志（7 天）长得多，因为这是**计费与合规**的凭据：话单对不上时
     * 要能翻回来看「这条到底发没发出去、谁发的」。一天最多几十行，90 天也就几千行。
     */
    private static final int RETENTION_DAYS = 90;

    private final SmsOutboundRepository outboundRepository;
    private final SmsOutboundService outboundService;

    /**
     * 专给 {@link #purgeOld()} 用的编程式事务。
     *
     * <p>批删走的是 {@code deleteCreatedBefore}，那是个 {@code @Modifying} 语句，
     * **必须在可写事务里执行**；而 Spring Data 给查询方法默认挂只读事务，
     * Hibernate 会直接拒绝。这个类身上也没有 {@code @Transactional}，
     * 而给 private 方法挂注解等于没挂（自调用不经过代理）。
     */
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        try {
            markUnknown();
        } catch (Exception e) {
            log.error("Failed to mark stuck outbound SMS as unknown", e);
        }
        try {
            purgeOld();
        } catch (Exception e) {
            log.error("Failed to purge old outbound SMS records", e);
        }
    }

    private void markUnknown() {
        List<SmsOutbound> stuck = outboundService.findStuckDispatched(
                LocalDateTime.now().minusMinutes(UNKNOWN_AFTER_MINUTES), BATCH_SIZE);
        if (stuck.isEmpty()) {
            return;
        }

        for (SmsOutbound outbound : stuck) {
            // 记事件由 service 做（那里已经有受控文案），这里只管状态推进
            outboundService.markUnknown(outbound);
        }
        log.warn("把 {} 条「已下发但一直没回执」的外发短信标成了结果未知", stuck.size());
    }

    private void purgeOld() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        Integer removed = transactionTemplate.execute(
                status -> outboundRepository.deleteCreatedBefore(cutoff));
        if (removed != null && removed > 0) {
            log.info("清理了 {} 条超过 {} 天的外发短信记录", removed, RETENTION_DAYS);
        }
    }
}
