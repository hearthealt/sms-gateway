package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceCommandAckItem;
import com.smsgateway.model.dto.DeviceCommandPayload;
import com.smsgateway.model.dto.DeviceCommandView;
import com.smsgateway.model.entity.DeviceCommand;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.DeviceCommandType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceCommandRepository;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.service.notify.AlertOutbox;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 远程指令的服务端行为。
 *
 * <p>这组测试里最重要的三条，都是「看起来只是实现细节、实际是安全或正确性边界」的那种：
 *
 * <ol>
 *   <li><b>重新注册不接受参数</b> —— 挡的是经下行通道下发新的重注册密钥。那会让
 *       {@code app.secret.key} 泄漏后无法通过轮换止损。</li>
 *   <li><b>回执必须限定在本设备名下</b> —— 少了作用域判定，拿 A 设备令牌就能把
 *       B 设备的指令标成「已执行」。</li>
 *   <li><b>探测心跳只下发「启动网关」</b> —— 用户停网关的意图是「这台机器冻结住」，
 *       从 WorkManager 上下文里执行「清理已上传记录」是在违背这个意图改本机状态。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceCommandServiceTest {

    private static final String DEVICE_CODE = "android-abc";
    private static final Long DEVICE_PK = 42L;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private EventLogService eventLogService;

    @Mock
    private AdminEventBroadcaster adminEvents;

    /**
     * 告警入队口：指令执行失败时要主动叫人。
     *
     * <p>**缺了它就是 NPE**：{@code @InjectMocks} 只会注入「有对应 @Mock 的」参数，
     * 而生产里它由 Spring 注入、从来不会是 null。
     */
    @Mock
    private AlertOutbox alertOutbox;

    @InjectMocks
    private DeviceCommandService service;

    private SmsDevice device;

    @BeforeEach
    void setUp() {
        device = new SmsDevice();
        device.setId(DEVICE_PK);
        device.setDeviceId(DEVICE_CODE);
        device.setDeviceName("车间手机");

        when(deviceRepository.findByDeviceId(DEVICE_CODE)).thenReturn(Optional.of(device));
        when(commandRepository.findFirstByDeviceIdAndCommandTypeAndStatusInOrderByIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ 签发

    @Test
    @DisplayName("签发时按类型定死过期时刻，并落库（不在查询时算）")
    void expiryIsFixedPerTypeAtIssueTime() {
        ArgumentCaptor<DeviceCommand> captor = ArgumentCaptor.forClass(DeviceCommand.class);
        LocalDateTime before = LocalDateTime.now();

        service.issue(DEVICE_CODE, DeviceCommandType.STOP_GATEWAY, null, "admin");

        verify(commandRepository).save(captor.capture());
        DeviceCommand saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DeviceCommandStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getIssuedBy()).isEqualTo("admin");
        assertThat(saved.getDeviceId()).isEqualTo(DEVICE_PK);
        assertThat(saved.getDeviceCode()).isEqualTo(DEVICE_CODE);
        // 1 小时（±几秒容差）。落库而不是查询时算，是为了让当时的策略可审计：
        // 将来调整了 TTL，历史记录的解读不会跟着漂移。
        assertThat(saved.getExpiresAt())
                .isBetween(before.plusHours(1).minusSeconds(5), LocalDateTime.now().plusHours(1).plusSeconds(5));
    }

    @Test
    @DisplayName("重新注册的 TTL 只有 10 分钟：它是唯一成批改写服务端字段的指令")
    void reRegisterHasShorterTtl() {
        ArgumentCaptor<DeviceCommand> captor = ArgumentCaptor.forClass(DeviceCommand.class);
        LocalDateTime before = LocalDateTime.now();

        service.issue(DEVICE_CODE, DeviceCommandType.RE_REGISTER, null, "admin");

        verify(commandRepository).save(captor.capture());
        // 一周之后才送达的重注册会把设备早已走过的旧值（deviceName / phone /
        // appVersion）重新写回服务端 —— 那是一条过期的指令在制造数据回退。
        assertThat(captor.getValue().getExpiresAt())
                .isBetween(before.plusMinutes(10).minusSeconds(5), LocalDateTime.now().plusMinutes(10).plusSeconds(5));
    }

    @Test
    @DisplayName("重新注册带参数一律拒绝，且指路「生成恢复码」")
    void reRegisterRejectsAnyArgument() {
        // 这条测试守的是一个刻意的设计决定，不是参数校验。
        //
        // 下发一份新的 enrollSecret 会给攻击者一条跨越密钥轮换的路径：拿到
        // app.secret.key 的人本来就能冒充任意设备，但轮换主密钥会立刻止损；
        // 而有了这条通道，他可以在被清理前换一份新密钥，轮换之后继续控制那台设备。
        // 要拆掉这个拒绝，得先推翻上面那段分析。
        assertThatThrownBy(() ->
                service.issue(DEVICE_CODE, DeviceCommandType.RE_REGISTER, "brand-new-secret", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("恢复码")
                .hasMessageContaining("app.secret.key");

        verify(commandRepository, never()).save(any());
        verifyNoInteractions(eventLogService);
    }

    @Test
    @DisplayName("修改号码必须带参数，且参数要能归一化出足够的数字位")
    void setPhoneValidatesArgument() {
        assertThatThrownBy(() -> service.issue(DEVICE_CODE, DeviceCommandType.SET_PHONE, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供参数");

        assertThatThrownBy(() -> service.issue(DEVICE_CODE, DeviceCommandType.SET_PHONE, "123", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 位数字");

        verify(commandRepository, never()).save(any());
    }

    @Test
    @DisplayName("不需要参数的类型收到参数时报错，而不是静默丢掉")
    void nonArgumentTypeRejectsArgument() {
        // 静默丢掉参数会让调用方以为它生效了。这个项目的一贯做法是宁可响亮地失败：
        // 「写死的东西不会配错」—— 连「允许传但不作数」这种半截状态都不留。
        assertThatThrownBy(() -> service.issue(DEVICE_CODE, DeviceCommandType.REUPLOAD, "13800138000", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不接受参数");
    }

    @Test
    @DisplayName("同类型已有未结束的指令时返回已有的那条，不新建")
    void duplicateIssueReturnsExistingOpenCommand() {
        DeviceCommand existing = command(7L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.SENT);
        when(commandRepository.findFirstByDeviceIdAndCommandTypeAndStatusInOrderByIdDesc(
                eq(DEVICE_PK), eq(DeviceCommandType.STOP_GATEWAY), any()))
                .thenReturn(Optional.of(existing));

        DeviceCommandView view = service.issue(DEVICE_CODE, DeviceCommandType.STOP_GATEWAY, null, "admin");

        assertThat(view.getId()).isEqualTo(7L);
        assertThat(view.getStatus()).isEqualTo("SENT");
        // 连点两下不该产生两条记录：第二条的效果与第一条完全相同，而它只会
        // 在列表上制造「怎么有两条、是不是要执行两次」的疑问。
        verify(commandRepository, never()).save(any());
    }

    @Test
    @DisplayName("设备不存在时签发报错，而不是签一条永远送不出去的指令")
    void issueForUnknownDeviceFails() {
        when(deviceRepository.findByDeviceId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue("ghost", DeviceCommandType.STOP_GATEWAY, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备不存在");
    }

    // ------------------------------------------------------------------ 下发

    @Test
    @DisplayName("下发：转 SENT、attempts 加一、90 秒后才允许再发，且视图只给四个字段")
    void claimMarksSentAndSchedulesRedelivery() {
        DeviceCommand pending = command(11L, DeviceCommandType.SET_PHONE, DeviceCommandStatus.PENDING);
        pending.setArgument("13800138000");
        when(commandRepository.findDeliverable(eq(DEVICE_PK), anyCollection(), anyCollection(),
                eq(DeviceCommandService.MAX_ATTEMPTS), any(), any()))
                .thenReturn(List.of(pending));

        List<DeviceCommandPayload> payloads = service.claimForDelivery(DEVICE_CODE, false);

        assertThat(payloads).hasSize(1);
        DeviceCommandPayload payload = payloads.get(0);
        assertThat(payload.getId()).isEqualTo(11L);
        assertThat(payload.getType()).isEqualTo("SET_PHONE");
        assertThat(payload.getArgument()).isEqualTo("13800138000");
        assertThat(payload.getExpiresAt()).isEqualTo(pending.getExpiresAt());

        // 下发视图**刻意只有四个字段**。status / attempts / issuedBy 是管理端的运维信息，
        // 设备一个都不需要 —— 多一个字段就是多一处信息暴露面。
        assertThat(DeviceCommandPayload.class.getDeclaredFields()).hasSize(4);

        assertThat(pending.getStatus()).isEqualTo(DeviceCommandStatus.SENT);
        assertThat(pending.getAttempts()).isEqualTo(1);
        // 大于心跳周期（30 秒），否则每次心跳都会把它重塞一遍。
        assertThat(pending.getNextDeliverAt()).isAfter(LocalDateTime.now().plusSeconds(80));
    }

    @Test
    @DisplayName("探测心跳只捞「启动网关」一条")
    void probeOnlyDeliversStartGateway() {
        service.claimForDelivery(DEVICE_CODE, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<DeviceCommandType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(commandRepository).findDeliverable(eq(DEVICE_PK), anyCollection(), types.capture(),
                anyInt(), any(), any());

        // 用户停网关的意图是「这台机器冻结住，短信留在本地」。从 WorkManager 上下文里
        // 执行「清理已上传记录」「改号码」是在违背这个意图改本机状态，而且用户看不见。
        assertThat(types.getValue()).containsExactly(DeviceCommandType.START_GATEWAY);
    }

    @Test
    @DisplayName("常规心跳捞全部类型（不是只捞「启动网关」）")
    void regularHeartbeatDeliversAllTypes() {
        service.claimForDelivery(DEVICE_CODE, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<DeviceCommandType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(commandRepository).findDeliverable(eq(DEVICE_PK), anyCollection(), types.capture(),
                anyInt(), any(), any());

        assertThat(types.getValue()).containsExactlyInAnyOrder(DeviceCommandType.values());
    }

    @Test
    @DisplayName("设备行已不存在时不查指令（静默返回空，不抛）")
    void claimForUnknownDeviceReturnsEmpty() {
        when(deviceRepository.findByDeviceId("ghost")).thenReturn(Optional.empty());

        assertThat(service.claimForDelivery("ghost", false)).isEmpty();

        verify(commandRepository, never()).findDeliverable(any(), anyCollection(), anyCollection(),
                anyInt(), any(), any());
    }

    // ------------------------------------------------------------------ 回执

    @Test
    @DisplayName("回执必须限定在本设备名下：别人的指令一条都不改")
    void ackIgnoresCommandsOfOtherDevices() {
        // 仓储按 (ids, devicePk) 过滤，所以不属于本设备的 id 根本查不出来。
        // 这条断言守的是那个筛选条件：没有它，拿 A 设备令牌就能把 B 设备的指令
        // 标成「已执行」，管理端于是看到一台从未动作过的设备「已经停好了」。
        when(commandRepository.findByIdInAndDeviceId(List.of(99L), DEVICE_PK)).thenReturn(List.of());

        int accepted = service.ack(DEVICE_CODE, List.of(item(99L, "DONE", null)));

        assertThat(accepted).isZero();
        verify(commandRepository, never()).saveAll(any());
        verifyNoInteractions(eventLogService);
    }

    @Test
    @DisplayName("回执 DONE 转「已执行」并记事件")
    void ackDoneMarksAcked() {
        DeviceCommand sent = command(12L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(12L), DEVICE_PK)).thenReturn(List.of(sent));

        int accepted = service.ack(DEVICE_CODE, List.of(item(12L, "DONE", "网关已停止")));

        assertThat(accepted).isEqualTo(1);
        assertThat(sent.getStatus()).isEqualTo(DeviceCommandStatus.ACKED);
        assertThat(sent.getAckedAt()).isNotNull();
        assertThat(sent.getResultDetail()).isEqualTo("网关已停止");
        verify(eventLogService).record(eq(EventType.DEVICE_COMMAND_ACKED), eq(device), anyString());
    }

    @Test
    @DisplayName("回执 REJECTED 与 FAILED 一样是终态失败")
    void ackRejectedMarksFailed() {
        DeviceCommand sent = command(13L, DeviceCommandType.CLEAR_UPLOADED, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(13L), DEVICE_PK)).thenReturn(List.of(sent));

        service.ack(DEVICE_CODE, List.of(item(13L, "REJECTED", "本机版本不支持该指令")));

        assertThat(sent.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        verify(eventLogService).record(eq(EventType.DEVICE_COMMAND_FAILED), eq(device), anyString());
    }

    @Test
    @DisplayName("重复回执幂等：不改动、不报错，但仍算「认下」——否则设备会永远重发")
    void duplicateAckIsIdempotentButStillAccepted() {
        DeviceCommand acked = command(14L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.ACKED);
        acked.setAckedAt(LocalDateTime.now().minusMinutes(1));
        acked.setResultDetail("网关已停止");
        when(commandRepository.findByIdInAndDeviceId(List.of(14L), DEVICE_PK)).thenReturn(List.of(acked));

        int accepted = service.ack(DEVICE_CODE, List.of(item(14L, "DONE", "网关已停止")));

        // 「认下」= 属于本设备且服务端收到了这条回执。返回改动数的话，
        // 一次重发（0 改动）会被设备理解成「服务端没收到」，于是永远重发下去。
        assertThat(accepted).isEqualTo(1);
        assertThat(acked.getStatus()).isEqualTo(DeviceCommandStatus.ACKED);
        verify(commandRepository, never()).saveAll(any());
        verifyNoInteractions(eventLogService);
    }

    @Test
    @DisplayName("不认识的回执状态报 400 级错误，不静默当成成功")
    void unknownAckStatusIsRejected() {
        DeviceCommand sent = command(15L, DeviceCommandType.REUPLOAD, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(15L), DEVICE_PK)).thenReturn(List.of(sent));

        assertThatThrownBy(() -> service.ack(DEVICE_CODE, List.of(item(15L, "MAYBE", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不认识的回执状态");

        assertThat(sent.getStatus()).isEqualTo(DeviceCommandStatus.SENT);
    }

    @Test
    @DisplayName("停止网关被执行后，服务端自己补一次主动报停")
    void ackedStopGatewayMarksDeviceReportedOffline() {
        // 设备执行远程停机时也会自己调一次 /api/device/offline，但那是尽力而为的。
        // 少了服务端这一手，上报失败时这次停机会被判成「心跳超时掉线」，
        // 而紧接着上线的故障告警就会把管理员**自己刚做的事**报成一条「设备离线」。
        DeviceCommand sent = command(31L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(31L), DEVICE_PK)).thenReturn(List.of(sent));

        service.ack(DEVICE_CODE, List.of(item(31L, "DONE", "网关已停止")));

        assertThat(device.getReportedOfflineAt()).isNotNull();
        verify(deviceRepository).save(device);
    }

    @Test
    @DisplayName("其他指令被执行时不碰在线状态")
    void otherCommandAcksDoNotTouchPresence() {
        // 顺手把设备标成离线是错的：一条「触发重传」执行完之后设备还在正常上报，
        // 而 reported_offline_at 一写，后台立刻把它显示成掉线。
        DeviceCommand sent = command(32L, DeviceCommandType.REUPLOAD, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(32L), DEVICE_PK)).thenReturn(List.of(sent));

        service.ack(DEVICE_CODE, List.of(item(32L, "DONE", "已触发重传")));

        assertThat(device.getReportedOfflineAt()).isNull();
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("停止网关**执行失败**时不报停 —— 网关还在跑")
    void failedStopGatewayDoesNotMarkOffline() {
        DeviceCommand sent = command(33L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.SENT);
        when(commandRepository.findByIdInAndDeviceId(List.of(33L), DEVICE_PK)).thenReturn(List.of(sent));

        service.ack(DEVICE_CODE, List.of(item(33L, "FAILED", "执行异常")));

        assertThat(device.getReportedOfflineAt()).isNull();
        verify(deviceRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    @DisplayName("已结束的指令不能撤销")
    void cancelTerminalCommandIsRejected() {
        DeviceCommand acked = command(21L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.ACKED);
        when(commandRepository.findById(21L)).thenReturn(Optional.of(acked));

        assertThatThrownBy(() -> service.cancel(21L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法撤销");

        assertThat(acked.getStatus()).isEqualTo(DeviceCommandStatus.ACKED);
    }

    @Test
    @DisplayName("已下发但没回执的指令可以撤销 —— 这是阻止它生效的唯一手段")
    void cancelSentCommandWorks() {
        DeviceCommand sent = command(22L, DeviceCommandType.STOP_GATEWAY, DeviceCommandStatus.SENT);
        when(commandRepository.findById(22L)).thenReturn(Optional.of(sent));

        DeviceCommandView view = service.cancel(22L);

        assertThat(view.getStatus()).isEqualTo("CANCELLED");
        assertThat(sent.getStatus()).isEqualTo(DeviceCommandStatus.CANCELLED);
        verify(eventLogService).recordWithIdentity(
                eq(EventType.DEVICE_COMMAND_CANCELLED), eq(DEVICE_PK), eq(DEVICE_CODE), anyString());
    }

    // ------------------------------------------------------------------ 辅助

    private static DeviceCommand command(Long id, DeviceCommandType type, DeviceCommandStatus status) {
        DeviceCommand command = new DeviceCommand();
        command.setId(id);
        command.setDeviceId(DEVICE_PK);
        command.setDeviceCode(DEVICE_CODE);
        command.setCommandType(type);
        command.setStatus(status);
        command.setNextDeliverAt(LocalDateTime.now());
        command.setExpiresAt(LocalDateTime.now().plusHours(1));
        command.setCreatedAt(LocalDateTime.now());
        return command;
    }

    private static DeviceCommandAckItem item(Long id, String status, String detail) {
        return new DeviceCommandAckItem(id, status, detail);
    }
}
