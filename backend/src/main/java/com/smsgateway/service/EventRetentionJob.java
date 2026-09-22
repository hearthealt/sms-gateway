package com.smsgateway.service;

import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 运行日志的保留策略。
 *
 * <p>形状逐条照抄 {@link SmsRetentionJob}（清醒着每分钟、到点才干活；分批、每批一个
 * 独立事务；进程内记「今天跑过没有」）。这里只写与它**不同**的地方：
 *
 * <ul>
 *   <li><b>没有「先删关联再删主表」的顺序约束。</b>短信那边有子表（投递记录只存
 *       sms_message_id、且没有外键），所以必须在同一个事务里先删子表。事件表没有子表，
 *       删就是删 —— 这一点不用照抄。</li>
 *   <li><b>默认保留 7 天而不是 0。</b>短信默认不清理是因为每行都存着正文与验证码，
 *       留多久该由合规要求决定；事件表只有「谁在什么时候发生了什么」，体积与暴露面
 *       都小得多，给一个合理默认值是安全的。</li>
 *   <li><b>反方向要清</b>：短信被 {@link SmsRetentionJob} 删掉之后，事件行上的
 *       sms_message_id 会悬空 —— 那个清理在短信那一侧做（同一个事务里），见那里的注释。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetentionJob {

    private final EventLogRepository eventLogRepository;
    private final TransactionTemplate transactionTemplate;
    private final SysConfigService sysConfigService;

    /** 到点后多久之内都算「该跑了」。给调度漂移留余量，但又不能宽到「下午重启也触发」。 */
    private static final int DUE_WINDOW_MINUTES = 10;

    /** 每批删除的行数。设小一点是为了让每批都能快速提交、尽快释放锁。 */
    @Value("${app.event.cleanup-batch-size:1000}")
    private int batchSize;

    /**
     * 每分钟醒一次，到点才真干活。
     *
     * <p>不用 {@code @Scheduled(cron = ...)}：清理时刻是**运行期可调**的
     * （管理后台「系统设置」页改完立即生效），而 cron 表达式是启动时读的。
     * 理由与 {@link SmsRetentionJob} 完全一致。
     */
    @Scheduled(fixedDelay = 60_000)
    public void purgeOldEvents() {
        int retentionDays = sysConfigService.getInt(SysConfigKey.EVENT_RETENTION_DAYS);
        if (retentionDays <= 0) {
            return;
        }
        if (!isDueNow()) {
            return;
        }

        LocalDateTime cutoff = LocalDate.now().minusDays(retentionDays).atStartOfDay();
        int total = 0;

        // 每批一个独立事务：一次删完会把锁持有到天荒地老。
        while (true) {
            Integer removed = transactionTemplate.execute(status -> purgeBatch(cutoff));

            if (removed == null || removed == 0) {
                break;
            }
            total += removed;
            if (removed < batchSize) {
                break;
            }
        }

        if (total > 0) {
            log.info("Purged {} event_log rows older than {} days (before {})",
                    total, retentionDays, cutoff);
        }
    }

    private int purgeBatch(LocalDateTime cutoff) {
        List<Long> ids = eventLogRepository.findIdsByCreatedAtBefore(cutoff, batchSize);
        if (ids.isEmpty()) {
            return 0;
        }
        return eventLogRepository.deleteByIdIn(ids);
    }

    /** 今天是否已经跑过。进程内标记，重启会重置 —— 清理本身幂等，多跑一次删不掉东西。 */
    private volatile LocalDate lastRunDate;

    private boolean isDueNow() {
        LocalDate today = LocalDate.now();
        if (today.equals(lastRunDate)) {
            return false;
        }

        LocalTime target = parseConfiguredTime();
        if (target == null) {
            return false;
        }

        LocalTime now = LocalTime.now();
        boolean due = !now.isBefore(target) && now.isBefore(target.plusMinutes(DUE_WINDOW_MINUTES));
        if (due) {
            lastRunDate = today;
        }
        return due;
    }

    /** 配置里是 {@code HH:mm}。格式不对时记一条日志并跳过当天 —— 不能让调度线程一直抛。 */
    private LocalTime parseConfiguredTime() {
        String raw = sysConfigService.get(SysConfigKey.EVENT_CLEANUP_TIME);
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            log.warn("系统设置里的运行日志清理时间 '{}' 不是合法的 HH:mm，本次跳过。请到「系统设置」页改正。", raw);
            return null;
        }
    }

    /** 启动时说一声当前策略 —— 否则「日志一直在涨」这件事没有任何地方会提示。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reportPolicy() {
        int retentionDays = sysConfigService.getInt(SysConfigKey.EVENT_RETENTION_DAYS);
        if (retentionDays <= 0) {
            log.warn("运行日志保留策略未启用（保留天数 = 0）：event_log 只增不减。"
                    + "需要清理请到管理后台「系统设置 → 运行日志保留天数」里改，改完立即生效。");
        } else {
            log.info("运行日志保留 {} 天，每天 {} 清理，每批 {} 行",
                    retentionDays, sysConfigService.get(SysConfigKey.EVENT_CLEANUP_TIME), batchSize);
        }
    }
}
