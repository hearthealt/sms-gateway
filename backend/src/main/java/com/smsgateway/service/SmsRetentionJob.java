package com.smsgateway.service;

import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

/**
 * 短信历史的保留策略。
 *
 * <p>原先 {@code sms_message} 只增不减：整个项目里没有任何清理或归档任务，
 * 而每一行都存着短信全文与提取出的验证码 —— 表体积、备份体积、以及数据暴露面
 * 都随时间只增不减。
 *
 * <p><b>默认不清理</b>（保留天数 = 0）。这是一条删数据的策略，保留多久取决于
 * 合规与业务要求，不该由代码替你决定 —— 所以它做成了管理后台「系统设置」里的一项，
 * 改完**立即生效、不用重启**（原先在 application.yml 里，改一次就要重启一次）。
 *
 * 设成大于 0 之后，每天凌晨按批删除早于该天数的记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsRetentionJob {

    private final SmsMessageRepository smsMessageRepository;
    private final TransactionTemplate transactionTemplate;
    private final SysConfigService sysConfigService;

    /** 到点后多久之内都算「该跑了」。给调度漂移留余量，但又不能宽到「下午重启也触发」。 */
    private static final int DUE_WINDOW_MINUTES = 10;

    /** 每批删除的行数。设小一点是为了让每批都能快速提交、尽快释放锁。 */
    @Value("${app.sms.cleanup-batch-size:1000}")
    private int batchSize;

    /**
     * 每分钟醒一次，到点才真干活。
     *
     * <p><b>为什么不用 {@code @Scheduled(cron = ...)}</b>：cron 表达式是启动时读的，
     * 而「每天几点清理」现在是**运行期可调**的（在「系统设置」页改）。想让它跟着配置走，
     * 要么用 {@code SchedulingConfigurer} 重建 trigger，要么就这一招 —— 常醒 + 判断。
     * 每分钟一次的空转代价可以忽略，而转发调度器那边已经用过同一招了。
     *
     * <p>「今天跑过没有」只记在进程内，所以**重启后当天可能再跑一次**。
     * 这没关系：清理本身是幂等的（删的是「早于 N 天」的行，第二次跑什么也删不掉）。
     */
    @Scheduled(fixedDelay = 60_000)
    public void purgeOldMessages() {
        // 保留天数从库里读：它是运维过程中会想调的，改完立即生效、不用重启。
        // 每次跑都读一次（SysConfigService 带进程内缓存，不打库）。
        int retentionDays = sysConfigService.getInt(SysConfigKey.SMS_RETENTION_DAYS);
        if (retentionDays <= 0) {
            return;
        }
        if (!isDueNow()) {
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

    /** 今天是否已经跑过。进程内标记，重启会重置 —— 见 {@link #purgeOldMessages()} 的说明。 */
    private volatile LocalDate lastRunDate;

    /**
     * 现在该不该跑。
     *
     * <p>用「到点了 **且** 在一个窗口内」而不是「正好等于某一分钟」：调度是
     * 「上一轮跑完再等 60 秒」，会有漂移，卡死某一分钟必然隔三差五地漏掉。
     *
     * <p>而窗口**不能开得太宽**：开成「今天到点了就跑」的话，服务在下午重启会立刻
     * 触发一次清理 —— 那不是「每天 03:30 清理」的语义，而且删数据这种事不该在
     * 一个意外的时间点自己发生。
     */
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
        String raw = sysConfigService.get(SysConfigKey.SMS_CLEANUP_TIME);
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            log.warn("系统设置里的清理时间 '{}' 不是合法的 HH:mm，本次跳过。请到「系统设置」页改正。", raw);
            return null;
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
        int retentionDays = sysConfigService.getInt(SysConfigKey.SMS_RETENTION_DAYS);

        if (retentionDays <= 0) {
            log.warn("短信历史保留策略未启用（保留天数 = 0）："
                    + "sms_message 只增不减，每行都存着短信全文与验证码。"
                    + "需要清理请到管理后台「系统设置 → 短信保留天数」里改，改完立即生效。");
        } else {
            log.info("短信历史保留 {} 天，每天 {} 清理，每批 {} 行",
                    retentionDays, sysConfigService.get(SysConfigKey.SMS_CLEANUP_TIME), batchSize);
        }
    }
}
