package com.smsgateway.service;

import com.smsgateway.repository.SmsMessageRepository;
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

/**
 * 短信历史的保留策略。
 *
 * <p>原先 {@code sms_message} 只增不减：整个项目里没有任何清理或归档任务，
 * 而每一行都存着短信全文与提取出的验证码 —— 表体积、备份体积、以及数据暴露面
 * 都随时间只增不减。
 *
 * <p><b>默认不清理</b>（{@code app.sms.retention-days=0}）。这是一条删数据的策略，
 * 保留多久取决于合规与业务要求，不该由代码替你决定。启用方式：
 *
 * <pre>
 *   APP_SMS_RETENTION_DAYS=180
 * </pre>
 *
 * 设成大于 0 之后，每天凌晨按批删除早于该天数的记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsRetentionJob {

    private final SmsMessageRepository smsMessageRepository;
    private final TransactionTemplate transactionTemplate;

    /** 保留天数。0（默认）表示不清理。 */
    @Value("${app.sms.retention-days:0}")
    private int retentionDays;

    /** 每批删除的行数。设小一点是为了让每批都能快速提交、尽快释放锁。 */
    @Value("${app.sms.cleanup-batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${app.sms.cleanup-cron:0 30 3 * * *}")
    public void purgeOldMessages() {
        if (retentionDays <= 0) {
            return;
        }

        LocalDateTime cutoff = LocalDate.now().minusDays(retentionDays).atStartOfDay();
        int total = 0;

        // 每批一个独立事务：一次删完会把锁持有到天荒地老，
        // 而分批之后每批提交即释放，同库的短信写入不会被长时间堵住。
        while (true) {
            Integer removed = transactionTemplate.execute(
                    status -> smsMessageRepository.deleteBatchByReceiveTimeBefore(cutoff, batchSize));

            if (removed == null || removed == 0) {
                break;
            }
            total += removed;
            if (removed < batchSize) {
                break;
            }
        }

        if (total > 0) {
            log.info("Purged {} sms_message rows older than {} days (before {})",
                    total, retentionDays, cutoff);
        }
    }

    /**
     * 没配保留天数时说一声。
     *
     * <p>否则「表格一直在涨」这件事没有任何地方会提示，等到发现时可能已经积累了几百万行
     * —— 而每行都是一条真实的短信内容。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDisabled() {
        if (retentionDays <= 0) {
            log.warn("短信历史保留策略未启用（app.sms.retention-days=0）："
                    + "sms_message 只增不减，每行都存着短信全文与验证码。"
                    + "需要清理请设置 APP_SMS_RETENTION_DAYS（单位：天）。");
        } else {
            log.info("短信历史保留 {} 天，每天 {} 清理，每批 {} 行",
                    retentionDays, "03:30", batchSize);
        }
    }
}
