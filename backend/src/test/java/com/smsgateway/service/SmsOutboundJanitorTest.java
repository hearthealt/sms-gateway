package com.smsgateway.service;

import com.smsgateway.model.entity.SmsOutbound;
import com.smsgateway.model.enums.SmsOutboundStatus;
import com.smsgateway.repository.SmsOutboundRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外发短信的收尾任务。
 *
 * <p>两件事：把「下发了但一直没回执」判成**结果未知**（那是这份状态机里最诚实的一格），
 * 以及清掉超过 90 天的计费凭据。
 *
 * <p>批删必须走 {@link TransactionTemplate} —— 那个坑在 {@code DeviceCommandJanitor}
 * 上踩过一次：{@code @Modifying} 语句需要可写事务，而 Spring Data 给查询方法默认挂
 * 只读事务，Hibernate 会直接拒绝。这里也钉一遍，免得下次有人「简化」掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsOutboundJanitorTest {

    @Mock private SmsOutboundRepository outboundRepository;
    @Mock private SmsOutboundService outboundService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private SmsOutboundJanitor janitor;

    private static SmsOutbound dispatched(long id) {
        SmsOutbound row = new SmsOutbound();
        row.setId(id);
        row.setDeviceId(42L);
        row.setDeviceCode("android-abc");
        row.setPhone("13800138000");
        row.setContent("提醒内容");
        row.setStatus(SmsOutboundStatus.DISPATCHED);
        row.setOutboundKey("out-key-" + id);
        row.setDispatchedAt(LocalDateTime.now().minusMinutes(30));
        return row;
    }

    @Test
    @DisplayName("下发超过 10 分钟没回执的判成结果未知")
    void stuckDispatchedBecomesUnknown() {
        SmsOutbound row = dispatched(1L);
        when(outboundService.findStuckDispatched(any(), anyInt())).thenReturn(List.of(row));

        janitor.run();

        // 状态推进交给 service（那里已经有受控文案与事件）
        verify(outboundService).markUnknown(row);

        // 时间窗是「10 分钟前」—— 比它新的一律不动
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboundService).findStuckDispatched(captor.capture(), anyInt());
        assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusMinutes(9));
        assertThat(captor.getValue()).isAfter(LocalDateTime.now().minusMinutes(11));
    }

    @Test
    @DisplayName("没有卡住的行时什么都不做")
    void nothingStuck() {
        when(outboundService.findStuckDispatched(any(), anyInt())).thenReturn(List.of());

        janitor.run();

        verify(outboundService, org.mockito.Mockito.never()).markUnknown(any());
    }

    @Test
    @DisplayName("批删走可写事务 —— 只读事务里 Hibernate 会拒绝 @Modifying")
    void purgeRunsInWritableTransaction() {
        when(outboundService.findStuckDispatched(any(), anyInt())).thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        assertThatCode(() -> janitor.run()).doesNotThrowAnyException();

        verify(outboundRepository).deleteCreatedBefore(any());
    }

    @Test
    @DisplayName("一轮里任一环节抛异常都不会带走整个任务")
    void failureIsSwallowed() {
        when(outboundService.findStuckDispatched(any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("db down"));

        // @Scheduled 的 fixedDelay 会继续触发，但异常抛出去会污染日志、掩盖真正的原因
        assertThatCode(() -> janitor.run()).doesNotThrowAnyException();
    }
}
