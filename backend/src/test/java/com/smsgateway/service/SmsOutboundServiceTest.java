package com.smsgateway.service;

import com.smsgateway.model.dto.ClientOutboundView;
import com.smsgateway.model.dto.OutboundResult;
import com.smsgateway.model.dto.SmsOutboundPayload;
import com.smsgateway.model.dto.SmsSendRequest;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsOutbound;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.SmsOutboundStatus;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsOutboundRepository;
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
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外发短信的服务端行为。
 *
 * <p>这组测试守的是**一个会花钱、且不可撤回的动作**，所以三条最要紧的都在这里：
 *
 * <ol>
 *   <li><b>绝不重发</b>：下发过的绝不出现在下一次心跳里 —— 重发是真的又发一条出去。</li>
 *   <li><b>作用域</b>：拿着 A 设备令牌的人不能把 B 设备的外发记录标成「已发出」。</li>
 *   <li><b>每日上限</b>：一次误操作不该把一台设备变成短信轰炸机。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsOutboundServiceTest {

    private static final String DEVICE_CODE = "android-abc";
    private static final Long DEVICE_PK = 42L;

    @Mock private SmsOutboundRepository outboundRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private EventLogService eventLogService;
    @Mock private AdminEventBroadcaster adminEvents;
    @Mock private SysConfigService sysConfigService;

    @InjectMocks private SmsOutboundService service;

    private SmsDevice device;

    @BeforeEach
    void setUp() {
        device = new SmsDevice();
        device.setId(DEVICE_PK);
        device.setDeviceId(DEVICE_CODE);
        device.setDeviceName("车间手机");
        when(deviceRepository.findByDeviceId(DEVICE_CODE)).thenReturn(Optional.of(device));
        when(sysConfigService.getInt(SysConfigKey.OUTBOUND_DAILY_LIMIT_PER_DEVICE)).thenReturn(20);
        when(outboundRepository.countByDeviceIdAndCreatedAtGreaterThanEqual(anyLong(), any()))
                .thenReturn(0L);
    }

    private static SmsSendRequest request(String phone, String content) {
        SmsSendRequest request = new SmsSendRequest();
        request.setPhone(phone);
        request.setContent(content);
        request.setDeviceId(DEVICE_CODE);
        return request;
    }

    private static SmsOutbound outbound(long id, SmsOutboundStatus status) {
        SmsOutbound row = new SmsOutbound();
        row.setId(id);
        row.setDeviceId(DEVICE_PK);
        row.setDeviceCode(DEVICE_CODE);
        row.setPhone("13800138000");
        row.setContent("提醒内容");
        row.setStatus(status);
        row.setSource("ADMIN");
        row.setOutboundKey("out-key-" + id);
        return row;
    }

    // ------------------------------------------------------------------ 入队

    @Test
    @DisplayName("入队：状态 PENDING、生成幂等键、记一条事件")
    void enqueueCreatesPendingRow() {
        ArgumentCaptor<SmsOutbound> captor = ArgumentCaptor.forClass(SmsOutbound.class);

        service.enqueue(DEVICE_CODE, request("13800138000", "提醒内容"), "admin");

        verify(outboundRepository).save(captor.capture());
        SmsOutbound saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SmsOutboundStatus.PENDING);
        assertThat(saved.getSource()).isEqualTo("ADMIN");
        assertThat(saved.getCreatedBy()).isEqualTo("admin");
        // 幂等键必须带上前缀且非空：设备回执时原样带回，靠它认领
        assertThat(saved.getOutboundKey()).startsWith("out-");
        verify(eventLogService).record(eq(EventType.SMS_OUTBOUND_QUEUED), eq(device), anyString());
    }

    @Test
    @DisplayName("每日上限：达到上限直接拒绝，不建记录")
    void dailyLimitBlocks() {
        when(sysConfigService.getInt(SysConfigKey.OUTBOUND_DAILY_LIMIT_PER_DEVICE)).thenReturn(3);
        when(outboundRepository.countByDeviceIdAndCreatedAtGreaterThanEqual(anyLong(), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.enqueue(DEVICE_CODE, request("13800138000", "hi"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每日上限");

        verify(outboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("上限为 0 表示不限")
    void zeroLimitMeansUnlimited() {
        when(sysConfigService.getInt(SysConfigKey.OUTBOUND_DAILY_LIMIT_PER_DEVICE)).thenReturn(0);
        when(outboundRepository.countByDeviceIdAndCreatedAtGreaterThanEqual(anyLong(), any()))
                .thenReturn(9999L);

        service.enqueue(DEVICE_CODE, request("13800138000", "hi"), "admin");

        verify(outboundRepository).save(any());
        // 不限时连数都不该数一次
        verify(outboundRepository, never())
                .countByDeviceIdAndCreatedAtGreaterThanEqual(anyLong(), any());
    }

    @Test
    @DisplayName("被禁用的设备不能被用来发短信 —— 它的心跳照常，少了这道闸短信真会发出去")
    void disabledDeviceIsRejected() {
        device.setStatus(AdminDeviceService.STATUS_DISABLED);

        assertThatThrownBy(() -> service.enqueue(DEVICE_CODE, request("13800138000", "hi"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被禁用");

        verify(outboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("正文超过 500 字拒绝 —— 这条是要计费的")
    void contentTooLongIsRejected() {
        String tooLong = "啊".repeat(SmsOutboundService.MAX_CONTENT_CHARS + 1);

        assertThatThrownBy(() -> service.enqueue(DEVICE_CODE, request("13800138000", tooLong), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
    }

    @Test
    @DisplayName("号码数字不足 5 位拒绝")
    void phoneTooShortIsRejected() {
        assertThatThrownBy(() -> service.enqueue(DEVICE_CODE, request("123", "hi"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("号码格式不正确");
    }

    @Test
    @DisplayName("外部接口省略 deviceId：服务器上只有一台设备时用它")
    void clientUsesTheOnlyDevice() {
        when(deviceRepository.findAll()).thenReturn(List.of(device));

        SmsSendRequest request = new SmsSendRequest();
        request.setPhone("13900139000");
        request.setContent("hi");
        ClientOutboundView view = service.enqueueFromClient(request, "某业务系统");

        assertThat(view.getPhone()).isEqualTo("13900139000");
        assertThat(view.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("外部接口省略 deviceId：有多台时拒绝，要求显式指定")
    void clientRefusesWhenAmbiguous() {
        // 这一条守的是「别拿收信方的号码去猜设备」：发错手机的代价是
        // 「用别人的号发了一条短信」，而调用方从返回里看不出选错了。
        SmsDevice other = new SmsDevice();
        other.setId(43L);
        other.setDeviceId("android-other");
        when(deviceRepository.findAll()).thenReturn(List.of(device, other));

        SmsSendRequest request = new SmsSendRequest();
        request.setPhone("13900139000");
        request.setContent("hi");

        assertThatThrownBy(() -> service.enqueueFromClient(request, "某业务系统"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法确定用哪一台")
                .hasMessageContaining("deviceId");
    }

    @Test
    @DisplayName("外部接口：一台设备都没有时拒绝，并指路")
    void clientRejectsWhenNoDevice() {
        when(deviceRepository.findAll()).thenReturn(List.of());

        SmsSendRequest request = new SmsSendRequest();
        request.setPhone("13900139000");
        request.setContent("hi");

        assertThatThrownBy(() -> service.enqueueFromClient(request, "某业务系统"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("还没有任何设备");
    }

    // ------------------------------------------------------------------ 下发

    @Test
    @DisplayName("下发：PENDING → DISPATCHED，并带回幂等键与正文")
    void claimMarksDispatched() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.PENDING);
        when(outboundRepository.findDeliverable(eq(DEVICE_PK), eq(SmsOutboundStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(row));

        List<SmsOutboundPayload> payloads = service.claimForDelivery(DEVICE_CODE);

        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).getKey()).isEqualTo("out-key-1");
        assertThat(payloads.get(0).getContent()).isEqualTo("提醒内容");
        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.DISPATCHED);
        assertThat(row.getDispatchedAt()).isNotNull();
    }

    @Test
    @DisplayName("绝不重发：第二次下发时仓储已经没有 PENDING 可捞（查询条件只挑 PENDING）")
    void claimNeverRepeats() {
        // 第一次交出去
        SmsOutbound row = outbound(1L, SmsOutboundStatus.PENDING);
        when(outboundRepository.findDeliverable(eq(DEVICE_PK), eq(SmsOutboundStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(row));
        service.claimForDelivery(DEVICE_CODE);
        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.DISPATCHED);

        // 第二次：仓储按 status=PENDING 过滤，已 DISPATCHED 的不在其中
        when(outboundRepository.findDeliverable(eq(DEVICE_PK), eq(SmsOutboundStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.claimForDelivery(DEVICE_CODE)).isEmpty();
    }

    // ------------------------------------------------------------------ 回执

    @Test
    @DisplayName("回执 SENT：转已发出、记 sentAt 与设备报来的分段数")
    void ackSent() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.DISPATCHED);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        OutboundResult result = new OutboundResult("out-key-1", "SENT", null, null);
        result.setSegments(2);
        int accepted = service.applyResults(DEVICE_CODE, List.of(result));

        assertThat(accepted).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        // 分段数直接等于计费条数，只有设备知道它那张卡走的是 GSM-7 还是 UCS-2
        assertThat(row.getSegments()).isEqualTo(2);
        verify(eventLogService).record(eq(EventType.SMS_OUTBOUND_SENT), eq(device), anyString());
    }

    @Test
    @DisplayName("回执 DELIVERED 同时记投递时刻")
    void ackDeliveredRecordsDeliveryTime() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.DISPATCHED);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        service.applyResults(DEVICE_CODE, List.of(new OutboundResult("out-key-1", "DELIVERED", null, null)));

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.SENT);
        assertThat(row.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("回执 FAILED：记原因")
    void ackFailed() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.DISPATCHED);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        service.applyResults(DEVICE_CODE,
                List.of(new OutboundResult("out-key-1", "FAILED", "RESULT_ERROR_NO_SERVICE", null)));

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.FAILED);
        assertThat(row.getErrorReason()).isEqualTo("RESULT_ERROR_NO_SERVICE", null);
        assertThat(row.getSentAt()).isNull();
    }

    @Test
    @DisplayName("作用域：不属于本设备的 key 一条都不改")
    void ackIsScopedToDevice() {
        // 仓储按 (keys, devicePk) 过滤，别人的 key 根本查不出来
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of());

        int accepted = service.applyResults(DEVICE_CODE,
                List.of(new OutboundResult("out-key-of-other-device", "SENT", null, null)));

        assertThat(accepted).isZero();
        verify(outboundRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("不认识的状态：跳过，不猜成成功也不猜成失败")
    void ackIgnoresUnknownStatus() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.DISPATCHED);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        service.applyResults(DEVICE_CODE, List.of(new OutboundResult("out-key-1", "MAYBE", null, null)));

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.DISPATCHED);
        assertThat(row.getErrorReason()).isNull();
    }

    @Test
    @DisplayName("重复回执幂等：已经终态的跳过，但仍算「认下」")
    void ackIsIdempotent() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.SENT);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        int accepted = service.applyResults(DEVICE_CODE,
                List.of(new OutboundResult("out-key-1", "SENT", null, null)));

        // 设备会在每一次心跳里重复带上未确认的结果（它不知道服务端收没收到），
        // 所以这里必须幂等：不改动，但返回「认下」让它停止重报
        assertThat(accepted).isEqualTo(1);
        verify(outboundRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("迟到的回执能覆盖「结果未知」—— 真相比「不知道」好")
    void lateAckOverwritesUnknown() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.UNKNOWN);
        when(outboundRepository.findByOutboundKeyInAndDeviceId(any(), eq(DEVICE_PK)))
                .thenReturn(List.of(row));

        service.applyResults(DEVICE_CODE, List.of(new OutboundResult("out-key-1", "SENT", null, null)));

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.SENT);
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    @DisplayName("已下发给设备的不能撤销 —— 它可能已经发出去了")
    void cannotCancelDispatched() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.DISPATCHED);
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法撤销");

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.DISPATCHED);
    }

    @Test
    @DisplayName("待下发的可以撤销")
    void cancelPendingWorks() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.PENDING);
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(row));

        service.cancel(1L);

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.CANCELLED);
        verify(eventLogService).recordWithIdentity(
                eq(EventType.SMS_OUTBOUND_CANCELLED), eq(DEVICE_PK), eq(DEVICE_CODE), anyString());
    }

    // ------------------------------------------------------------------ 重发

    @Test
    @DisplayName("重发：只从 FAILED 回到待下发，并清掉上一次的失败原因")
    void retryFailedReturnsToPending() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.FAILED);
        row.setErrorReason("本机未授予「发送短信」权限");
        row.setDispatchedAt(java.time.LocalDateTime.now().minusMinutes(5));
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(row));

        service.retry(1L);

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.PENDING);
        assertThat(row.getErrorReason()).isNull();
        // dispatchedAt 也要清：留着它，下一次下发时「交给设备的时刻」会是上一轮的
        assertThat(row.getDispatchedAt()).isNull();
    }

    @Test
    @DisplayName("**「结果未知」的绝不重发** —— 它可能已经发出去了，重发会重复计费")
    void retryRefusesUnknown() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.UNKNOWN);
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计费两次");

        assertThat(row.getStatus()).isEqualTo(SmsOutboundStatus.UNKNOWN);
    }

    @Test
    @DisplayName("已发出的也不能重发（那就是真的再发一条）")
    void retryRefusesSent() {
        SmsOutbound row = outbound(1L, SmsOutboundStatus.SENT);
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有「发送失败」的能重发");
    }

    @Test
    @DisplayName("列表：把设备名一起带上（一次批量查，不逐条 findById）")
    void listCarriesDeviceName() {
        when(outboundRepository.search(eq(false), eq(SmsOutboundStatus.PENDING), any(), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        new ArrayList<>(List.of(outbound(1L, SmsOutboundStatus.PENDING)))));
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));

        var result = service.list(null, "PENDING", 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getDeviceName()).isEqualTo("车间手机");
        assertThat(result.getRecords().get(0).getStatusLabel()).isEqualTo("待下发");
    }
}
