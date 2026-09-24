package com.smsgateway.service.notify;

import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 离线告警的扫描循环。
 *
 * <p><b>「谁算离线」那个判据不在这里测，它在 DeviceRepository.findSilentSince 的 JPQL 里</b>
 * —— 三个条件（未禁用、心跳过、是沉默下去而不是说过再见的）没有一个是这个类自己判的。
 * 那三条只有连着数据库才验得了，所以它们由端到端验证覆盖（见 README 的验证一节），
 * 而不是在这里用 mock 假装验过。
 *
 * <p>这里测的是这个类自己的两件事：**同一台设备不会反复告警**，
 * 以及**恢复之后要把故障期清掉**（否则它下一次掉线就再也不告警了）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfflineAlertWatcherTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private AlertOutbox alertOutbox;
    @Mock private AlertGate alertGate;
    @Mock private SysConfigService sysConfigService;

    @InjectMocks private OfflineAlertWatcher watcher;

    @BeforeEach
    void setUp() {
        when(sysConfigService.getInt(SysConfigKey.ALERT_OFFLINE_AFTER_MINUTES)).thenReturn(10);
    }

    private SmsDevice device(String deviceCode, String name) {
        SmsDevice device = new SmsDevice();
        device.setId(42L);
        device.setDeviceId(deviceCode);
        device.setDeviceName(name);
        device.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(32));
        return device;
    }

    @Test
    @DisplayName("沉默的设备告警一次；下一轮扫描不再重复")
    void alertsOncePerEpisode() {
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", "车间手机")));

        watcher.sweep();
        watcher.sweep();

        // 第二轮被进程内的集合挡下（省一次 Redis 往返）。真正的去重仍在 AlertGate 里，
        // 那份状态跨重启，所以这里少发一次不影响正确性。
        verify(alertOutbox, times(1)).enqueue(
                eq(AlertType.DEVICE_OFFLINE), eq("device:android-abc"), anyString());
    }

    @Test
    @DisplayName("恢复在线的设备：清掉故障期，让下次离线能重新告警")
    void recoveredDeviceClearsEpisode() {
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", "车间手机")));
        watcher.sweep();

        // 它回来了
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of());
        watcher.sweep();

        verify(alertGate).clearEpisode(AlertType.DEVICE_OFFLINE, "device:android-abc");
    }

    @Test
    @DisplayName("恢复之后再次离线：重新告警（故障期已清）")
    void alertsAgainAfterRecovery() {
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", "车间手机")));
        watcher.sweep();
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of());
        watcher.sweep();
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", "车间手机")));
        watcher.sweep();

        verify(alertOutbox, times(2)).enqueue(
                eq(AlertType.DEVICE_OFFLINE), eq("device:android-abc"), anyString());
    }

    @Test
    @DisplayName("一直沉默的设备不会被反复清故障期")
    void stillSilentDeviceKeepsEpisode() {
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", "车间手机")));

        watcher.sweep();
        watcher.sweep();

        // 清掉的话「沉默中」的设备每轮都会重新告警一次，冷却键也挡不住 ——
        // 因为清掉之后故障期就空着，每次都算新的一次。
        verify(alertGate, never()).clearEpisode(any(), anyString());
    }

    @Test
    @DisplayName("扫描窗口用的是配置的分钟数")
    void cutoffUsesConfiguredMinutes() {
        when(sysConfigService.getInt(SysConfigKey.ALERT_OFFLINE_AFTER_MINUTES)).thenReturn(30);
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusMinutes(30);
        watcher.sweep();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deviceRepository).findSilentSince(captor.capture());
        // 容几秒的执行抖动
        assertThat(captor.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }

    @Test
    @DisplayName("设备名读不到时摘要里用业务标识兜底 —— 「哪台设备」不能是空白")
    void fallbackNameInSummary() {
        when(deviceRepository.findSilentSince(any())).thenReturn(List.of(device("android-abc", null)));

        watcher.sweep();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(alertOutbox).enqueue(eq(AlertType.DEVICE_OFFLINE), eq("device:android-abc"), summary.capture());
        assertThat(summary.getValue()).contains("android-abc");
    }

    @Test
    @DisplayName("仓储抛异常被吞掉，下一轮还能继续跑")
    void repositoryFailureIsSwallowed() {
        when(deviceRepository.findSilentSince(any())).thenThrow(new RuntimeException("db down"));

        watcher.sweep();

        verify(alertOutbox, never()).enqueue(any(), anyString(), anyString());
    }
}
