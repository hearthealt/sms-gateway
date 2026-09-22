package com.smsgateway.service;

import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 盯着「哪些设备在线」这件事，集合一变就推一条事件给管理后台。
 *
 * <p><b>为什么需要它。</b>设备上线有请求可挂钩（心跳进来时就知道），但**掉线没有** ——
 * 掉线是「一段时间没消息」推导出来的，没有任何一次调用可以顺带触发推送。
 * 于是这里主动定期算一遍。注意这**不是**让浏览器轮询：算在服务端、只在集合真的变了
 * 才发一条，浏览器平时一个请求都不发。
 *
 * <p>心跳 30 秒一次、超时阈值 90 秒，所以 15 秒扫一遍就够：一台设备停止上报之后，
 * 最迟 105 秒管理后台会自己变灰（如果设备是主动点「停止网关」，走 markOffline 那条，
 * 是立刻的）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevicePresenceWatcher {

    private final DeviceRepository deviceRepository;
    private final AdminEventBroadcaster adminEvents;
    private final EventLogService eventLogService;

    /** 上一次广播时的在线集合。只用来判断「变了没有」，不参与任何业务判定。 */
    private Set<Long> lastOnlineIds = Set.of();

    @Scheduled(fixedDelayString = "${app.presence.sweep-ms:15000}")
    public void sweep() {
        Set<Long> onlineIds;
        try {
            onlineIds = deviceRepository.findOnlineIds(DeviceService.onlineSince());
        } catch (Exception e) {
            // 一次查询失败不该让这个定时任务从此消失（fixedDelay 的任务抛异常后仍会继续，
            // 但日志里留一条，免得「后台不刷新」变成查不出原因的悬案）
            log.warn("Presence sweep failed", e);
            return;
        }

        if (onlineIds.equals(lastOnlineIds)) {
            return;
        }

        // 差集要在覆盖 lastOnlineIds **之前**算。
        Set<Long> wentOffline = new HashSet<>(lastOnlineIds);
        wentOffline.removeAll(onlineIds);

        log.debug("Online devices changed: {} -> {}", lastOnlineIds, onlineIds);
        lastOnlineIds = onlineIds;
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_DEVICES, Map.of("online", onlineIds.size()));

        recordOffline(wentOffline);
    }

    /**
     * 把刚掉线的设备记成事件。
     *
     * <p>放在这里而不是心跳里：**掉线没有请求可挂钩**，它是「一段时间没消息」推导出来的，
     * 只有这个定时任务知道。而且这个任务与有没有人订阅 SSE 无关 ——
     * 管理后台关着的时候掉线照样留痕，这正是排查「设备什么时候失联的」需要的东西。
     *
     * <p>进程刚启动时 {@code lastOnlineIds} 是空集，所以第一次扫描算不出差集、
     * 不会凭空记一批离线 —— 代价是「服务重启的窗口里掉线的那台」这次记不到，
     * 下一次集合变化时它会以 DEVICE_ONLINE 的形式补上。
     */
    private void recordOffline(Set<Long> wentOffline) {
        if (wentOffline.isEmpty()) {
            return;
        }

        // 批量取一次设备名，别逐条查（与 EventLogService.toViews 同一个理由）。
        for (SmsDevice device : deviceRepository.findAllById(wentOffline)) {
            try {
                eventLogService.record(EventType.DEVICE_OFFLINE, device, "心跳超时，判定为离线");
            } catch (Exception e) {
                // 一次记不上不该让整轮扫描停下 —— 后面还有别的设备要记。
                log.warn("Failed to record offline event for device={}", device.getDeviceId(), e);
            }
        }
    }
}
