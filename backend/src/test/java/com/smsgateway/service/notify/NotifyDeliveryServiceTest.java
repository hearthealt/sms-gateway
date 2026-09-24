package com.smsgateway.service.notify;

import com.smsgateway.model.dto.NotifyDeliveryView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.service.AdminEventBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递记录页的视图。
 *
 * <p>这组测试守的是加进告警之后**新出现**的那条错法：告警行的 {@code smsMessageId}
 * 天然为 null，而原先的判据是「sms == null → 显示『（原短信已删除）』」。
 * 不按来源分支的话，**每一行告警都会显示成「（原短信已删除）」** ——
 * 一句看起来很确定的谎话，排查时会把人引向完全错误的方向。
 *
 * <p>另一条是同一处的崩溃面：{@code findAllById} 里混进 null 会让 Hibernate 抛异常，
 * 整页投递记录都打不开。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyDeliveryServiceTest {

    @Mock private NotifyDeliveryRepository deliveryRepository;
    @Mock private SmsMessageRepository smsMessageRepository;
    @Mock private NotifyChannelRepository channelRepository;
    @Mock private AdminEventBroadcaster adminEvents;

    @InjectMocks private NotifyDeliveryService service;

    private NotifyChannel channel() {
        NotifyChannel channel = new NotifyChannel();
        channel.setId(10L);
        channel.setName("运维群");
        channel.setEnabled(true);
        return channel;
    }

    private void returnRows(NotifyDelivery... rows) {
        when(deliveryRepository.search(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rows)));
        when(channelRepository.findAll()).thenReturn(List.of(channel()));
    }

    @Test
    @DisplayName("告警行的预览是它自己的摘要，**不是**「（原短信已删除）」")
    void alertRowPreviewIsItsSummary() {
        NotifyDelivery alert = NotifyDelivery.alert(
                AlertType.DEVICE_OFFLINE, "device:android-abc", "设备「车间手机」已离线 32 分钟", 10L);
        alert.setId(1L);
        returnRows(alert);

        PageResult<NotifyDeliveryView> result = service.list(1, 20, null, null);

        NotifyDeliveryView view = result.getRecords().get(0);
        assertThat(view.getContentPreview()).isEqualTo("设备「车间手机」已离线 32 分钟");
        assertThat(view.getSourceType()).isEqualTo("ALERT");
        assertThat(view.getAlertTypeLabel()).isEqualTo("设备离线");
        // 告警没有关联短信，那几列本来就该是空的
        assertThat(view.getSender()).isNull();
        assertThat(view.getPhone()).isNull();
    }

    @Test
    @DisplayName("列表里混着告警行时不会把 null 塞进 findAllById")
    void listToleratesNullSmsMessageId() {
        NotifyDelivery alert = NotifyDelivery.alert(
                AlertType.CHANNEL_AUTO_DISABLED, "channel:7", "渠道挂了", 10L);
        alert.setId(1L);
        returnRows(alert);

        // findAllById([null]) 会让 Hibernate 直接抛异常，整页打不开
        assertThatCode(() -> service.list(1, 20, null, null)).doesNotThrowAnyException();

        // 断言「传进去的 id 里没有 null」而不是「没调用过」：告警行被滤掉之后
        // 剩下的是一个空表，而空表是可以安全传进去的（返回空结果）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(smsMessageRepository).findAllById(captor.capture());
        assertThat(captor.getValue()).doesNotContainNull();
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("短信行原样显示（回归：别把告警分支写到了短信前面）")
    void smsRowKeepsOriginalPreview() {
        NotifyDelivery sms = NotifyDelivery.pending(42L, 10L);
        sms.setId(2L);

        SmsMessage message = new SmsMessage();
        message.setId(42L);
        message.setSender("10690000");
        message.setPhone("13800138000");
        message.setContent("【某某】您的验证码是 483920。");

        returnRows(sms);
        when(smsMessageRepository.findAllById(any())).thenReturn(List.of(message));

        NotifyDeliveryView view = service.list(1, 20, null, null).getRecords().get(0);

        assertThat(view.getSourceType()).isEqualTo("SMS");
        assertThat(view.getContentPreview()).contains("您的验证码是 483920");
        assertThat(view.getAlertTypeLabel()).isNull();
    }

    @Test
    @DisplayName("短信被删掉时仍显示「（原短信已删除）」")
    void missingSmsKeepsDeletedMarker() {
        NotifyDelivery sms = NotifyDelivery.pending(42L, 10L);
        sms.setId(3L);
        returnRows(sms);
        when(smsMessageRepository.findAllById(any())).thenReturn(List.of());

        NotifyDeliveryView view = service.list(1, 20, null, null).getRecords().get(0);

        assertThat(view.getContentPreview()).isEqualTo("（原短信已删除）");
    }
}
