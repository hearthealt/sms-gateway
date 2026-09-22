package com.smsgateway.service;

import com.smsgateway.model.dto.EventLogView;
import com.smsgateway.model.dto.EventTypeOption;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.entity.EventLog;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.EventLevel;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.EventLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行事件的记录与查询。
 *
 * <p><b>{@link #record} 必须在业务事务之外调用。</b>这一点值得写进类注释，
 * 因为它和 Spring 的直觉是反的：
 *
 * <ul>
 *   <li>外层已经回滚了，事件仍然要留下 —— 「被拒」「令牌无效」「冲突」这类事件
 *       描述的正是**没成功**的那一次，跟着回滚就等于把要查的东西删了。</li>
 *   <li>所以标的是 {@code REQUIRES_NEW}，它另开一个事务、独立提交。</li>
 *   <li>但**不能**在业务事务里调它：那样事件会先于业务提交落库，
 *       万一业务随后回滚，「存下」这种事件就成了假记录 —— 而假记录比没记录更坏，
 *       它会让排查往完全错误的方向走。</li>
 * </ul>
 *
 * <p>结论：调用点放在业务方法**返回之后**（例如 {@code SmsService.receiveSms}
 * 里 {@code transactionTemplate.execute(...)} 拿到结果之后那一段）。
 */
@Slf4j
@Service
public class EventLogService {

    private final EventLogRepository eventLogRepository;
    private final DeviceRepository deviceRepository;
    private final AdminEventBroadcaster adminEvents;

    /**
     * 专用于「提交之后再记一条」的编程式事务。
     *
     * <p><b>为什么必须自己造一个，而不靠 {@code @Transactional}：</b>
     * afterCommit 回调里是 {@code this.recordXxx(...)} 自调用，**不经过 Spring 代理**，
     * 方法上的 {@code @Transactional(REQUIRES_NEW)} 一行都不会执行 —— 于是
     * {@code repository.save()} 加入的是那个**已经提交完**的事务，
     * 插入随着清理被丢掉，事件静默消失。这类「注解看起来生效、其实没生效」的坑
     * 只能靠不用注解来避免。
     *
     * <p>与 {@code SmsService} 用 {@link TransactionTemplate} 的理由同源：
     * 编程式事务不依赖代理，在哪调都真的开一个新事务。
     */
    private final TransactionTemplate requiresNewTx;

    public EventLogService(EventLogRepository eventLogRepository,
                           DeviceRepository deviceRepository,
                           AdminEventBroadcaster adminEvents,
                           PlatformTransactionManager transactionManager) {
        this.eventLogRepository = eventLogRepository;
        this.deviceRepository = deviceRepository;
        this.adminEvents = adminEvents;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        // 必须显式要 REQUIRES_NEW：TransactionTemplate 默认是 REQUIRED，
        // 那样它会去加入当前那个已提交的事务，等于白开。
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 与 event_log.reason 的列宽一致。超长截断，绝不因为一个长字符串把整条事件丢掉。 */
    private static final int REASON_MAX_LENGTH = 250;

    // ------------------------------------------------------------------ 记录

    /** 与设备无关的事件（例如渠道被自动停用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EventType type, String reason) {
        save(type, null, null, null, null, null, reason, null, null);
    }

    /** 认得出是哪台设备、但与本条短信无关的事件（注册、上下线、令牌无效）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EventType type, SmsDevice device, String reason) {
        save(type, pkOf(device), codeOf(device), null, null, null, reason, null, null);
    }

    /** 认得出是哪台设备、并且知道号码与发送方（上报被判重复/被拒）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EventType type, SmsDevice device, String sender, String phone, String reason) {
        save(type, pkOf(device), codeOf(device), null, sender, phone, reason, null, null);
    }

    /**
     * 与某条短信相关的上报结果。
     *
     * <p>带的是 {@code localMessageId} 与短信主键，**不带正文** —— 要看内容按
     * {@code smsMessageId} 去短信记录页查。这条界限是这张表存在的全部前提。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordForSms(EventType type, SmsDevice device, SmsMessage sms, String reason) {
        save(type,
                pkOf(device),
                codeOf(device),
                sms.getLocalMessageId(),
                sms.getSender(),
                sms.getPhone(),
                reason,
                sms.getId(),
                null);
    }

    private static Long pkOf(SmsDevice device) {
        return device == null ? null : device.getId();
    }

    private static String codeOf(SmsDevice device) {
        return device == null ? null : device.getDeviceId();
    }

    /**
     * 在**当前事务提交之后**记一条事件；当前没有事务时立即记。
     *
     * <p>给那些「本身就在写业务数据、又想顺手留一条痕」的调用点用（管理端的增删改）。
     * 那里直接在事务里调 {@link #record}（REQUIRES_NEW）会让事件先于业务提交落库 ——
     * 业务万一回滚，这条事件就成了假记录，而假记录比没有记录更坏。
     *
     * <p>没有 {@code @Transactional} 的地方不必用它，直接调 {@code record} 就行。
     */
    public void recordAfterCommit(EventType type, SmsDevice device, String reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(type, device, reason);
            return;
        }
        // 回调里不再碰实体：事务提交后它可能已经被删掉了（删设备那条路径），
        // 现在就把身份抄下来。
        Long devicePk = device == null ? null : device.getId();
        String deviceCode = device == null ? null : device.getDeviceId();
        registerAfterCommit(() -> recordWithIdentity(type, devicePk, deviceCode, reason));
    }

    /**
     * 同上，但只带设备业务标识、不带主键。
     *
     * <p>专给「设备刚被删掉」那条事件用：那时 {@code sms_device} 里的行已经没了，
     * 再写主键就是一个永远悬空的外键。保留 device_code 是为了列表上仍认得出删的是谁。
     */
    public void recordAfterCommit(EventType type, String deviceCode, String reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordWithIdentity(type, null, deviceCode, reason);
            return;
        }
        registerAfterCommit(() -> recordWithIdentity(type, null, deviceCode, reason));
    }

    private void registerAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 业务已经提交完了，这里再抛异常没有任何人能接住、也救不回什么。
                try {
                    action.run();
                } catch (Exception e) {
                    log.warn("Failed to record event after commit", e);
                }
            }
        });
    }

    /**
     * 带显式设备身份记一条（设备主键与/或业务标识）。
     *
     * <p><b>用编程式事务而不是 {@code @Transactional}：</b>这个方法会被
     * {@link #recordAfterCommit} 以 {@code this.xxx()} 的形式自调用，那条路径不经过
     * Spring 代理，注解不会生效。理由详见 {@link #requiresNewTx}。
     */
    public void recordWithIdentity(EventType type, Long devicePk, String deviceCode, String reason) {
        requiresNewTx.executeWithoutResult(
                status -> save(type, devicePk, deviceCode, null, null, null, reason, null, null));
    }

    /**
     * 落库并推一条 SSE。
     *
     * <p>刻意不 catch：写事件失败说明库有问题，那时**宁可让调用方看见异常**，
     * 也不要静默咽掉 —— 一个悄悄不再记录事件的排查工具比没有工具更危险。
     * （调用方是否愿意为此中断自己的主流程，由调用方决定。）
     */
    private void save(EventType type,
                      Long devicePk,
                      String deviceCode,
                      String localMessageId,
                      String sender,
                      String phone,
                      String reason,
                      Long smsMessageId,
                      LocalDateTime at) {
        EventLog entity = new EventLog();
        entity.setEventType(type);
        entity.setLevel(type.defaultLevel());
        entity.setDeviceId(devicePk);
        entity.setDeviceCode(truncate(deviceCode, 128));
        entity.setLocalMessageId(truncate(localMessageId, 128));
        entity.setSender(truncate(sender, 100));
        entity.setPhone(truncate(phone, 32));
        entity.setReason(truncate(reason, REASON_MAX_LENGTH));
        entity.setSmsMessageId(smsMessageId);
        entity.setCreatedAt(at);

        eventLogRepository.save(entity);

        // 只作为「该刷新了」的信号，前端不解析内容。
        // 用 singletonMap 而不是 Map.of：Map.of 遇到 null 值直接抛 NPE。
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_EVENTS,
                java.util.Collections.singletonMap("id", entity.getId()));
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 管理端的事件列表，条件均可选。
     *
     * @param deviceId 业务设备标识；传了但设备不存在时返回**空列表**而不是全量 ——
     *                 筛一台已经删掉的设备却把所有人的事件都倒出来，是最误导的答案
     */
    public PageResult<EventLogView> list(EventType type,
                                         EventLevel level,
                                         String deviceId,
                                         LocalDateTime from,
                                         LocalDateTime to,
                                         int page,
                                         int pageSize) {
        Long devicePk = null;
        if (deviceId != null && !deviceId.isBlank()) {
            devicePk = deviceRepository.findByDeviceId(deviceId.trim())
                    .map(SmsDevice::getId)
                    .orElse(-1L);
        }

        // 布尔开关 + 恒非空的占位值，理由见 EventLogRepository.search 的注释。
        Page<EventLog> result = eventLogRepository.search(
                type == null,
                type == null ? EventType.SMS_STORED : type,
                level == null,
                level == null ? EventLevel.INFO : level,
                devicePk,
                from,
                to,
                PageRequest.of(page - 1, pageSize));

        return PageResult.of(toViews(result.getContent()), result.getTotalElements(), page, pageSize);
    }

    /** 下拉框选项。顺序就是枚举的声明顺序 —— 按「上报结果 → 身份 → 在线 → 管理动作」分组排列。 */
    public List<EventTypeOption> types() {
        return Arrays.stream(EventType.values())
                .map(t -> new EventTypeOption(t.name(), t.label(), t.defaultLevel().name()))
                .toList();
    }

    /**
     * 批量转视图。
     *
     * <p>设备一次性查出来做成映射，避免逐条事件查一次设备（N+1）——
     * 与 {@code AdminSmsService.toViews} 同一套写法。
     *
     * <p>设备已经被删掉时回落到行上的 {@code deviceCode}：删设备会连同它的历史事件
     * 一起清掉，但删除动作本身留的那一条、以及设备消失后仍可能存在的零星事件，
     * 都得认得出是哪台。
     */
    private List<EventLogView> toViews(List<EventLog> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<Long> devicePks = new HashSet<>();
        for (EventLog row : rows) {
            if (row.getDeviceId() != null) {
                devicePks.add(row.getDeviceId());
            }
        }
        Map<Long, SmsDevice> devices = new HashMap<>();
        if (!devicePks.isEmpty()) {
            for (SmsDevice device : deviceRepository.findAllById(devicePks)) {
                devices.put(device.getId(), device);
            }
        }

        return rows.stream().map(row -> {
            SmsDevice device = row.getDeviceId() == null ? null : devices.get(row.getDeviceId());
            return new EventLogView(
                    row.getId(),
                    row.getEventType().name(),
                    row.getEventType().label(),
                    row.getLevel().name(),
                    device != null ? device.getDeviceId() : row.getDeviceCode(),
                    device != null ? device.getDeviceName() : null,
                    row.getSender(),
                    row.getPhone(),
                    row.getReason(),
                    row.getSmsMessageId(),
                    row.getCreatedAt());
        }).toList();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
