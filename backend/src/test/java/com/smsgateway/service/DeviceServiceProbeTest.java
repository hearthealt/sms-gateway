package com.smsgateway.service;

import com.smsgateway.model.dto.HeartbeatRequest;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「网关已停止」时的低频探测心跳。
 *
 * <p>这组测试守的是一条容易被顺手破坏的既有特性：**用户点了「停止网关」之后，
 * 管理后台要立刻变灰，而不是隔一阵子闪一下在线。**
 *
 * <p>探测心跳存在的理由是一个死结：远程指令搭心跳下发，而网关停了就没人发心跳 ——
 * 于是「启动网关」这条最需要在「已停」状态下送达的指令恰好送不到。解法是设备用一个
 * 15 分钟的周期任务发一种特殊心跳，而服务端**必须完全跳过在线状态更新**。
 * 少了这条约束，后台会每 15 分钟把一台已经停掉的设备显示成在线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceServiceProbeTest {

    private static final String DEVICE_ID = "android-abc";

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private SmsMessageRepository smsMessageRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private AdminEventBroadcaster adminEvents;

    @Mock
    private EventLogService eventLogService;

    @Mock
    private DeviceEnrollTokenService enrollTokenService;

    @InjectMocks
    private DeviceService deviceService;

    /** 心跳已经停了十分钟的设备：在线判定上它就是离线的。 */
    private static final LocalDateTime STALE_HEARTBEAT = LocalDateTime.now().minusMinutes(10);

    private SmsDevice device;

    @BeforeEach
    void setUp() {
        device = new SmsDevice();
        device.setId(42L);
        device.setDeviceId(DEVICE_ID);
        device.setStatus("ACTIVE");
        device.setLastHeartbeatAt(STALE_HEARTBEAT);

        when(deviceRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private HeartbeatRequest request(Boolean probe) {
        HeartbeatRequest request = new HeartbeatRequest();
        request.setDeviceId(DEVICE_ID);
        request.setCommandProbe(probe);
        return request;
    }

    @Test
    @DisplayName("探测心跳一个字段都不写：不更新心跳时间、不写在线键、不推事件")
    void probeHeartbeatTouchesNothing() {
        String status = deviceService.heartbeat(DEVICE_ID, request(Boolean.TRUE));

        assertThat(status).isEqualTo("ACTIVE");

        // 心跳时间没被顶到「刚刚」—— 这一条是「停止后立刻变灰」的全部依赖。
        assertThat(device.getLastHeartbeatAt()).isEqualTo(STALE_HEARTBEAT);

        verify(deviceRepository, never()).save(any());
        // Redis 在线键同样是「在线」的证据，写它等于绕开上面那条断言。
        verify(redisTemplate, never()).opsForValue();
        verify(adminEvents, never()).broadcast(anyString(), any());
        // 连事件都不该记：15 分钟一条「设备上线」，7 天就是几百行噪音。
        verifyNoInteractions(eventLogService);
    }

    @Test
    @DisplayName("常规心跳照旧：更新心跳时间、写在线键、掉线回来时推事件")
    void regularHeartbeatStillUpdatesPresence() {
        String status = deviceService.heartbeat(DEVICE_ID, request(null));

        assertThat(status).isEqualTo("ACTIVE");
        assertThat(device.getLastHeartbeatAt()).isAfter(STALE_HEARTBEAT);

        verify(deviceRepository).save(device);
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
        // 此前不在线，所以这一下是「恢复在线」，要推一条让后台立刻变绿。
        verify(adminEvents).broadcast(anyString(), any());
    }

    @Test
    @DisplayName("老版本 App 不带 commandProbe 字段时，行为与本次变更之前一致")
    void missingProbeFieldBehavesLikeRegularHeartbeat() {
        // 请求体里根本没有这个字段 —— 反序列化后是 null，不是 false。
        // 判断写成 !Boolean.FALSE.equals(...) 之类就会把老设备全部当成探测，
        // 于是它们从此不再更新在线状态，后台全变灰。
        deviceService.heartbeat(DEVICE_ID, request(null));

        assertThat(device.getLastHeartbeatAt()).isAfter(STALE_HEARTBEAT);
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
    }
}
