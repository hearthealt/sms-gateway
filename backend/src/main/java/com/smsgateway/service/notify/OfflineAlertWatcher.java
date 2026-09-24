package com.smsgateway.service.notify;

import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备离线告警：一台无人值守的机器悄悄掉线了，得有人知道。
 *
 * <p><b>为什么不挂在 {@code DevicePresenceWatcher} 上。</b>那个类每 15 秒扫一次，
 * 只负责「在线集合变了就推一条 SSE」—— 它的判据是**瞬时**的。拿它当告警触发点，
 * 网络抖一下就会发一条；而这里要的是「持续静默超过 N 分钟」，两件事的周期、日志、
 * 判定条件都不同。项目里已有「两个管不同事的定时任务不要合成一个」的先例。
 *
 * <p><b>判据从数据库推导，不依赖内存。</b>见
 * {@code DeviceRepository.findSilentSince}：条件与在线判定同源，所以不会出现
 * 「仪表盘说在线、告警说离线」；进程重启也不丢状态。真正需要跨重启记忆的东西
 * （「这次离线已经告过警了」）在 {@link AlertGate} 的 Redis 键里，不在内存里。
 *
 * <p>本类里那个 {@code alertedDeviceIds} 集合**只是省几次 Redis 往返的优化**，
 * 不是正确性来源 —— 正确性由 AlertGate 的两个键保证。所以它在重启后为空是安全的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineAlertWatcher {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final DeviceRepository deviceRepository;
    private final AlertOutbox alertOutbox;
    private final AlertGate alertGate;
    private final SysConfigService sysConfigService;

    /** 上一轮处于「离线且已告警」状态的设备标识。见类注释：只是优化。 */
    private final Set<String> alertedDeviceIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        try {
            List<SmsDevice> silent = deviceRepository.findSilentSince(
                    LocalDateTime.now().minusMinutes(offlineAfterMinutes()));

            Set<String> stillSilent = new HashSet<>();
            for (SmsDevice device : silent) {
                stillSilent.add(device.getDeviceId());
                if (alertedDeviceIds.contains(device.getDeviceId())) {
                    // 这一轮它早就告过警了，别再走一遍闸门（每台每分钟一次 Redis 往返没必要）
                    continue;
                }
                alertOutbox.enqueue(AlertType.DEVICE_OFFLINE,
                        AlertSubjects.device(device.getDeviceId()),
                        describe(device));
            }

            clearEpisodesForRecoveredDevices(stillSilent);

            alertedDeviceIds.clear();
            alertedDeviceIds.addAll(stillSilent);
        } catch (Exception e) {
            // 一轮失败不该让这个任务停摆，下一轮重来即可（同 DeviceCommandJanitor）。
            log.error("离线告警扫描失败", e);
        }
    }

    /**
     * 已经恢复在线（或已被处理）的设备：结束它的故障期。
     *
     * <p>这一步是「下次离线还能重新告警」的全部实现 —— AlertGate 的故障期键
     * 只在**这里**被清除（冷却键刻意不清，那正是防抖）。少了它，一台设备掉线一次之后
     * 就再也不会告警了，7 天之内。
     *
     * <p>本次**不做「恢复通知」**：用户没有要求它，而它会让通知量翻倍 ——
     * 一条「恢复了」的信息在现场并不需要立刻知道，看一眼后台就够了。
     */
    private void clearEpisodesForRecoveredDevices(Set<String> stillSilent) {
        for (String deviceCode : new HashSet<>(alertedDeviceIds)) {
            if (!stillSilent.contains(deviceCode)) {
                alertGate.clearEpisode(AlertType.DEVICE_OFFLINE, AlertSubjects.device(deviceCode));
            }
        }
    }

    /**
     * 摘要：说清是哪台、离线多久、最后一次心跳什么时候。
     *
     * <p>不带设备名时用设备标识兜底 —— 名字是设备自己上报的，可能为空；
     * 而「哪台设备」是这条告警的全部价值所在，不能是空白。
     */
    private String describe(SmsDevice device) {
        String name = device.getDeviceName() == null || device.getDeviceName().isBlank()
                ? device.getDeviceId()
                : device.getDeviceName();

        LocalDateTime last = device.getLastHeartbeatAt();
        String duration = "";
        String lastText = "";
        if (last != null) {
            long minutes = Math.max(0, Duration.between(last, LocalDateTime.now()).toMinutes());
            duration = "已离线 " + minutes + " 分钟";
            lastText = "（最后心跳 " + last.format(HH_MM) + "）";
        }

        return "设备「" + name + "」" + duration + "未上报" + lastText;
    }

    /**
     * 离线多久才告警（分钟）。
     *
     * <p>下限 1 分钟：填 0 会让每一台设备在两次心跳之间（30 秒）就被判成离线。
     * 上限 7 天同理 —— 填一个超大值等于这个功能不存在，而界面上看不出不对。
     */
    private int offlineAfterMinutes() {
        int configured = sysConfigService.getInt(SysConfigKey.ALERT_OFFLINE_AFTER_MINUTES);
        return Math.max(1, Math.min(7 * 24 * 60, configured));
    }
}
