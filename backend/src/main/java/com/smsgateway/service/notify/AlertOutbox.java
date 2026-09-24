package com.smsgateway.service.notify;

import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.service.AdminEventBroadcaster;
import com.smsgateway.service.EventLogService;
import com.smsgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 告警的入队侧：把「什么坏了」变成一行行待投递记录。
 *
 * <p>与 {@link NotifyOutbox} 同构，但**没有事务可依附** —— 告警不是由一次数据库写入
 * 触发的，而是由一个定时扫描或一次发送失败触发的。所以这里刻意不做「事务性发件箱」
 * 那套保证，转而用【入队前先过闸门】来保证不刷屏（见 {@link AlertGate}）。
 *
 * <p><b>整段吞异常。</b>告警是旁路能力：它出问题不该让设备离线判定、渠道停用、
 * 指令回执这些正事失败。与 {@code NotifyOutbox.enqueue} 是同一条铁律。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertOutbox {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 「总开关没开」这条提示的节流键。见 {@link #noticeSwitchOff}。 */
    private static final String SWITCH_OFF_NOTICE_KEY = "sms:alert:switch-off-notice";

    private static final Duration SWITCH_OFF_NOTICE_TTL = Duration.ofHours(1);

    private final SysConfigService sysConfigService;
    private final StringRedisTemplate redisTemplate;
    private final AlertRuleEngine ruleEngine;
    private final AlertGate alertGate;
    private final NotifyDeliveryRepository deliveryRepository;
    private final EventLogService eventLogService;
    private final AdminEventBroadcaster adminEvents;

    /**
     * 入队一条告警。
     *
     * @param type              告警类型
     * @param subjectKey        告警主体（见 {@link AlertSubjects}），同时是去重键
     * @param summary           一句话摘要，会**落库**并作为消息正文的一部分
     * @param excludeChannelIds 本次要排除的渠道。**渠道自动停用那一类必须把刚死掉的
     *                          渠道排掉** —— 它已经发不出去了；不排的话那条投递会被
     *                          调度器的「停用渠道清扫」改成「已取消」，也就是一条
     *                          **静默取消**的告警，而它本来是要去叫人的。
     */
    public void enqueue(AlertType type, String subjectKey, String summary, Set<Long> excludeChannelIds) {
        // 总开关关着时告警也不发：它走的是同一条投递链路，理由与 NotifyOutbox 一致。
        // 这里刻意不再加一个 alert.enabled —— 见 SysConfigKey 里那段说明。
        //
        // **但必须留一条痕。** 这一条是踩过之后补的：管理员配好了告警规则、设备真的离线了，
        // 而运行日志里**一个字都没有** —— 因为这里直接 return 了。而「配了规则却没收到通知」
        // 正是这个功能最该解释清楚的一件事（A3 的全部意义就是别让失败静默）。
        // 短信转发那条路可以静默（总开关的说明就写在「系统设置」页上，而且每条短信都记一条
        // 会刷屏），告警不行：它本来就是**稀有事件**的通知。
        if (!sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)) {
            noticeSwitchOff(type);
            return;
        }

        try {
            Set<Long> channelIds = ruleEngine.matchChannels(type, subjectKey);
            if (excludeChannelIds != null && !excludeChannelIds.isEmpty()) {
                channelIds.removeAll(excludeChannelIds);
            }

            if (channelIds.isEmpty()) {
                // **这是让「告警发不出去」可见的唯一地方**，而它是最危险的静默状态：
                // 唯一一个渠道挂了，于是「渠道挂了」这件事没有人知道。
                // 事件行里带上主体，排查时能直接看出是哪台设备 / 哪个渠道。
                log.error("【故障告警无处可发】没有匹配到可用渠道：type={}, subject={}, summary={}",
                        type, subjectKey, summary);
                eventLogService.record(EventType.ALERT_UNDELIVERABLE,
                        type.label() + "：「" + summary + "」没有匹配到可用的转发渠道");
                return;
            }

            if (!alertGate.shouldFire(type, subjectKey)) {
                return;
            }

            LocalDateTime releaseAt = releaseAt();
            List<NotifyDelivery> deliveries = channelIds.stream()
                    .map(channelId -> {
                        NotifyDelivery delivery = NotifyDelivery.alert(type, subjectKey, summary, channelId);
                        // 静默时段：**推迟**而不是丢弃。见 QuietHours。
                        delivery.setNextRetryAt(releaseAt);
                        return delivery;
                    })
                    .toList();
            deliveryRepository.saveAll(deliveries);

            // 投递记录页的「该刷新了」信号，与 NotifyDispatcher 用的是同一个事件名。
            adminEvents.broadcast(AdminEventBroadcaster.EVENT_DELIVERIES,
                    Collections.singletonMap("id", deliveries.get(0).getId()));

            log.info("故障告警已入队：type={}, subject={}, channels={}, 投放时刻={}",
                    type, subjectKey, channelIds, releaseAt);
        } catch (Exception e) {
            log.error("故障告警入队失败（不影响主流程）：type={}, subject={}", type, subjectKey, e);
        }
    }

    /**
     * 「总开关没开，这条告警没发出去」的留痕。**一小时最多一条。**
     *
     * <p>不节流的话它是全项目最容易刷屏的一条：离线告警的扫描是每 60 秒一轮、
     * 每台静默设备都会走到这里 —— 一台设备离线一天就是 1440 条，
     * 而它要说明的事情一句话就说完了。
     *
     * <p>用 Redis 而不是进程内的标志位：重启后还能继续抑制（重启不该让刷屏重来一遍）。
     * Redis 不可用时**照常记录**，只是可能多记几条 —— 宁可多几条，也不要因为一个
     * 缓存组件挂了而把「为什么没告警」的答案一起吞掉。
     */
    private void noticeSwitchOff(AlertType type) {
        boolean shouldRecord = true;
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(
                    SWITCH_OFF_NOTICE_KEY, "1", SWITCH_OFF_NOTICE_TTL);
            shouldRecord = Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.debug("总开关提示的节流键写入失败，本条照常记录", e);
        }
        if (!shouldRecord) {
            return;
        }

        log.warn("【故障告警未发出】转发总开关（notify.enabled）未开启，本条告警被丢弃：type={}", type);
        try {
            eventLogService.record(EventType.ALERT_UNDELIVERABLE,
                    type.label() + "未发出 —— 转发总开关（系统设置 → 消息转发）没有打开。"
                            + "（同类提示每小时最多记一条）");
        } catch (Exception e) {
            log.warn("记「总开关未开」事件失败", e);
        }
    }

    /** 现在就能发时返回当前时刻；落在静默时段内则返回时段结束时刻。 */
    private LocalDateTime releaseAt() {
        LocalDateTime now = LocalDateTime.now();
        if (!sysConfigService.getBoolean(SysConfigKey.ALERT_QUIET_HOURS_ENABLED)) {
            return now;
        }

        LocalTime start = parseTime(SysConfigKey.ALERT_QUIET_HOURS_START);
        LocalTime end = parseTime(SysConfigKey.ALERT_QUIET_HOURS_END);
        // 两个都解析不出来时当作「没有静默时段」：宁可半夜发出去，也不要因为一个
        // 格式不对的值把所有告警永久推迟。
        if (start == null || end == null) {
            return now;
        }
        return QuietHours.nextRelease(now, start, end);
    }

    /**
     * 读一个 TIME 类型的配置项。
     *
     * <p>写入时 {@code SysConfigService} 已经校验过格式，但**库里可能被人直接改过**
     * （或回滚留下了旧值），所以这里解析失败返回 null 由调用方兜底，而不是抛异常 ——
     * 一个格式不对的配置不该让告警功能整个失效。
     */
    private LocalTime parseTime(SysConfigKey key) {
        return parseTimeValue(sysConfigService.get(key));
    }

    /** 单独抽出来是为了能被单测直接覆盖（不需要 SysConfigService）。 */
    static LocalTime parseTimeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim(), HH_MM);
        } catch (Exception e) {
            log.warn("静默时段配置格式不对，已忽略：{}", raw);
            return null;
        }
    }

    /** 便于调用方少写一个参数（多数告警没有要排除的渠道）。 */
    public void enqueue(AlertType type, String subjectKey, String summary) {
        enqueue(type, subjectKey, summary, Set.of());
    }
}
