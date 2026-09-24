package com.smsgateway.service;

import com.smsgateway.model.entity.DeviceCommand;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceCommandRepository;
import com.smsgateway.service.notify.AlertOutbox;
import com.smsgateway.service.notify.AlertSubjects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 远程指令的过期终结与保留清理。
 *
 * <p><b>为什么是独立组件而不是塞进 {@code NotifyDispatcher}：</b>那个类是转发领域的，
 * 而这条与转发毫无关系。项目里已有「两个管不同事的 60 秒任务不要合成一个」的先例
 * （{@code recoverStuck} 与 {@code cancelDeliveriesForDisabledChannels} 的注释：
 * 合成一个会让名字和内容对不上）。合成一个「什么都扫」的定时任务的下一步，
 * 就是没有人知道它到底负责什么。
 *
 * <p>终结一条指令有两种原因，**必须分别写进事件的 reason**：过了有效期，
 * 与「发给谁都不回执」。前者说明这件事已经失去意义（一周前下发的停止不该在设备
 * 回来后才生效），后者说明设备那头有问题（App 被强停、回执链路不通、客户端太旧）。
 * 两者的下一步动作完全不同。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceCommandJanitor {

    /** 一轮最多处理多少条。与事件清理一样分批，避免单个巨型事务。 */
    private static final int BATCH_SIZE = 200;

    /**
     * 保留期 30 天，硬编码，**不进 SysConfig**。
     *
     * <p>与 {@code sms.retention-days} / {@code event.retention-days} 的区别在于
     * 它们回答的是「用户数据留多久」（合规问题）；而这张表里只有「谁在什么时候点了
     * 哪个按钮」和一句受控文案，没有任何用户数据，一天最多长几十行。
     * 给它一个配置项，只会造出一个没人会调、调了也不会有人发现的表单项。
     */
    private static final int RETENTION_DAYS = 30;

    private static final Set<DeviceCommandStatus> OPEN_STATUSES =
            EnumSet.of(DeviceCommandStatus.PENDING, DeviceCommandStatus.SENT);

    private final DeviceCommandRepository commandRepository;
    private final EventLogService eventLogService;
    private final AlertOutbox alertOutbox;

    /**
     * 专给 {@link #purgeOld()} 用的编程式事务。
     *
     * <p><b>为什么必须自己开一个，而不给方法挂 {@code @Transactional}：</b>批删走的是
     * {@code deleteCreatedBefore}，那是个 {@code @Modifying} 语句，**必须在可写事务里执行**；
     * 而 Spring Data 给查询方法默认挂的是只读事务（{@code SimpleJpaRepository} 的类级
     * {@code @Transactional(readOnly = true)}），只读事务根本不开 JPA 事务 —— Hibernate 于是
     * 直接抛 {@code TransactionRequiredException: Executing an update/delete query}
     * （Spring 转成 {@code InvalidDataAccessApiUsageException} 抛出）。这个类身上也没有
     * 任何 {@code @Transactional}，所以它原先每轮都失败一次。
     *
     * <p>注解也解决不了：{@link #purgeOld()} 是 private，不经过代理，注解一行都不会执行
     * （同 {@code EventLogService} 类注释里那条）。而给 {@link #run()} 挂一个事务更糟 ——
     * {@link #expireExhausted()} 里的 {@code eventLogService.recordXxx} 标的是 REQUIRES_NEW，
     * 按那个类的约定必须在外层事务**之外**调用。
     */
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        try {
            expireExhausted();
        } catch (Exception e) {
            // 一轮失败不该让这个任务永久停摆（@Scheduled 的 fixedDelay 会继续触发，
            // 但把异常抛出去会污染日志且掩盖真正的原因）。下一轮重来即可。
            log.error("Failed to expire exhausted device commands", e);
        }
        try {
            purgeOld();
        } catch (Exception e) {
            log.error("Failed to purge old device commands", e);
        }
    }

    private void expireExhausted() {
        LocalDateTime now = LocalDateTime.now();
        List<DeviceCommand> exhausted = commandRepository.findExhausted(
                OPEN_STATUSES, DeviceCommandService.MAX_ATTEMPTS, now, PageRequest.of(0, BATCH_SIZE));
        if (exhausted.isEmpty()) {
            return;
        }

        for (DeviceCommand command : exhausted) {
            // 先判过期：两个条件可能同时成立，而「超过有效期」是更根本的那个原因
            // （attempts 用尽往往正是因为设备早就离线了）。
            boolean overdue = !command.getExpiresAt().isAfter(now);
            String reason = overdue
                    ? command.getCommandType().label() + "：超过有效期未送达（已下发 "
                            + command.getAttempts() + " 次）"
                    : command.getCommandType().label() + "：设备多次未回执，已放弃（已下发 "
                            + command.getAttempts() + " 次）";

            command.setStatus(DeviceCommandStatus.EXPIRED);
            commandRepository.save(command);

            eventLogService.recordWithIdentity(EventType.DEVICE_COMMAND_EXPIRED,
                    command.getDeviceId(), command.getDeviceCode(), reason);

            // 「指令没送达」也要主动叫人：管理员的预期是「点了它就生效」，
            // 而一条过期的指令意味着他的预期落空了 —— 只写事件表的话，
            // 他永远不知道自己那次点击没有生效。
            //
            // 摘要里用**业务标识**而不是设备名：这条路径上只带着 deviceCode
            // （设备行可能已经被删了，那正是留 deviceCode 的原因），为一个称呼
            // 再查一次库不划算，而 deviceCode 本来就认得出来是哪台。
            alertOutbox.enqueue(AlertType.DEVICE_COMMAND_FAILED,
                    AlertSubjects.device(command.getDeviceCode()),
                    "设备「" + command.getDeviceCode() + "」的远程指令「"
                            + command.getCommandType().label() + "」没有送达：" + reason);
        }

        log.info("Expired {} device command(s)", exhausted.size());
    }

    private void purgeOld() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);

        // 必须裹在事务里跑，理由见 transactionTemplate 字段的注释。
        // 与 SmsRetentionJob / EventRetentionJob 同一个形状：每批一个独立事务，
        // 提交即释放锁，同库的短信写入不会被这条 DELETE 拖住。
        Integer removed = transactionTemplate.execute(status -> commandRepository.deleteCreatedBefore(cutoff));
        if (removed != null && removed > 0) {
            log.info("Purged {} device command(s) older than {} days", removed, RETENTION_DAYS);
        }
    }
}
