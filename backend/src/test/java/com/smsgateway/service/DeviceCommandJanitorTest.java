package com.smsgateway.service;

import com.smsgateway.model.entity.DeviceCommand;
import com.smsgateway.model.enums.DeviceCommandStatus;
import com.smsgateway.model.enums.DeviceCommandType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceCommandRepository;
import com.smsgateway.service.notify.AlertOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清理任务的两种失败（以及它必须开事务的地方）。
 *
 * <p>这个类的第一版在**每一轮**都抛 {@code InvalidDataAccessApiUsageException:
 * Executing an update/delete query} —— {@code deleteCreatedBefore} 是 {@code @Modifying}
 * 语句，而 Spring Data 给查询方法默认挂的是只读事务，只读事务不开 JPA 事务，
 * Hibernate 于是拒绝执行更新/删除。这里的 {@code purgeUsesTransactionTemplate}
 * 就是钉住那个修复：批删必须从 {@link TransactionTemplate} 里走。
 */
@ExtendWith(MockitoExtension.class)
class DeviceCommandJanitorTest {

    /** 与 {@code DeviceCommandJanitor.RETENTION_DAYS} 一致。它是 private，只能写死在这里。 */
    private static final int RETENTION_DAYS = 30;

    private static final Long DEVICE_PK = 42L;
    private static final String DEVICE_CODE = "android-abc";

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private EventLogService eventLogService;

    /**
     * 告警入队口。过期终结时会经它叫一声（「指令没送达」也要主动通知）。
     *
     * <p>**这个 mock 是必需的，缺了它会「假通过」**：{@code run()} 把每一轮都包在
     * try/catch 里（一轮失败不该让定时任务停摆），于是 null 引发的 NPE 会被吞掉，
     * 只在日志里留下一行 ERROR —— 用例照样绿，而那条路径一次都没走通。
     */
    @Mock
    private AlertOutbox alertOutbox;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DeviceCommandJanitor janitor;

    @Test
    @DisplayName("保留清理必须走事务模板，且删的是 30 天以前的行")
    void purgeUsesTransactionTemplate() {
        // 模板替身要真的把回调跑起来，否则测的只是「回调被传进去了」，
        // 而删除语句本身有没有被执行根本没被验证。
        runCallbacksInline();
        when(commandRepository.deleteCreatedBefore(any())).thenReturn(3);

        LocalDateTime before = LocalDateTime.now();
        janitor.run();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(commandRepository).deleteCreatedBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isBetween(before.minusDays(RETENTION_DAYS).minusSeconds(5),
                        LocalDateTime.now().minusDays(RETENTION_DAYS).plusSeconds(5));

        // 这一条是整个修复的守卫：批删语句没有事务就是每轮一次的异常。
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("没有可清理的行时不删（模板返回 0 就到此为止）")
    void purgeDoesNothingWhenTemplateReportsZero() {
        runCallbacksInline();
        when(commandRepository.deleteCreatedBefore(any())).thenReturn(0);

        assertThatCode(() -> janitor.run()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("过了有效期或下发次数用尽的指令转 EXPIRED，并留一条带原因的事件")
    void expireMarksExhaustedCommandsAndRecordsEvent() {
        runCallbacksInline();
        // 两个条件同时成立时，「超过有效期」是更根本的那个原因 ——
        // attempts 用尽往往正是因为设备早就离线了。
        DeviceCommand overdue = command(7L, DeviceCommandType.STOP_GATEWAY);
        overdue.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        overdue.setAttempts(DeviceCommandService.MAX_ATTEMPTS);
        when(commandRepository.findExhausted(anyCollection(), anyInt(), any(), any()))
                .thenReturn(List.of(overdue));

        janitor.run();

        assertThat(overdue.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        verify(commandRepository).save(overdue);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(eventLogService).recordWithIdentity(
                eq(EventType.DEVICE_COMMAND_EXPIRED), eq(DEVICE_PK), eq(DEVICE_CODE), reason.capture());
        assertThat(reason.getValue())
                .contains("超过有效期")
                .contains("停止网关");
    }

    @Test
    @DisplayName("下发次数用尽但还没过期：原因是「设备多次未回执」，不是过期")
    void exhaustedAttemptsGetTheirOwnReason() {
        runCallbacksInline();
        DeviceCommand noAck = command(8L, DeviceCommandType.START_GATEWAY);
        noAck.setExpiresAt(LocalDateTime.now().plusHours(1));
        noAck.setAttempts(DeviceCommandService.MAX_ATTEMPTS);
        when(commandRepository.findExhausted(anyCollection(), anyInt(), any(), any()))
                .thenReturn(List.of(noAck));

        janitor.run();

        // 这两个原因的下一步动作完全不同：过期说明这件事已经失去意义，
        // 「没人回执」说明设备那头有问题（App 被强停、回执链路不通、客户端太旧）。
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(eventLogService).recordWithIdentity(any(), eq(DEVICE_PK), eq(DEVICE_CODE), reason.capture());
        assertThat(reason.getValue())
                .contains("未回执")
                .doesNotContain("超过有效期");
    }

    @Test
    @DisplayName("前半段失败不影响后半段：终结抛了，保留清理照跑")
    void purgeStillRunsWhenExpireThrows() {
        runCallbacksInline();
        when(commandRepository.findExhausted(anyCollection(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        // 一轮失败不该让任务停摆（这个任务本身每分钟再触发一次），
        // 但两类清扫是各自独立的，前者的异常不该把后者一起带走。
        assertThatCode(() -> janitor.run()).doesNotThrowAnyException();
        verify(commandRepository).deleteCreatedBefore(any());
    }

    // ------------------------------------------------------------------ 辅助

    /** 让 {@code transactionTemplate.execute(cb)} 真的执行 {@code cb}，返回它的结果。 */
    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            return callback.doInTransaction(status);
        });
    }

    private static DeviceCommand command(Long id, DeviceCommandType type) {
        DeviceCommand command = new DeviceCommand();
        command.setId(id);
        command.setDeviceId(DEVICE_PK);
        command.setDeviceCode(DEVICE_CODE);
        command.setCommandType(type);
        command.setStatus(DeviceCommandStatus.SENT);
        command.setNextDeliverAt(LocalDateTime.now());
        command.setCreatedAt(LocalDateTime.now());
        return command;
    }
}
