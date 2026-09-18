package com.smsgateway.service;

import com.smsgateway.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

        log.debug("Online devices changed: {} -> {}", lastOnlineIds, onlineIds);
        lastOnlineIds = onlineIds;
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_DEVICES, Map.of("online", onlineIds.size()));
    }
}
