package com.smsgateway.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsgateway.config.NotifyProperties;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.enums.AlertType;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.model.enums.NotifyDeliveryStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.AdminEventBroadcaster;
import com.smsgateway.service.EventLogService;
import com.smsgateway.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递结果的落库。
 *
 * <p>这组测试守的是一条**曾经破过、而且破了不会报错**的规矩：**成功的结果不得在记录上
 * 留下失败原因**。破的时候表现是——界面上状态是绿的成功，旁边却挂着一句
 * 「HTTP 200：{"code":0,"msg":"success"}」，现场据此以为转发一直在失败。
 *
 * <p>根因是 {@code SendResult.describe()} 对成功的结果照样会拼出一句「HTTP 200：{...}」
 * （它只是把状态码和响应体连起来），而当时的代码无条件把它写进了 {@code last_error}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyDispatcherTest {

    @Mock private NotifyProperties properties;
    @Mock private SysConfigService sysConfigService;
    @Mock private NotifyDeliveryRepository deliveryRepository;
    @Mock private NotifyChannelRepository channelRepository;
    @Mock private SmsMessageRepository smsMessageRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private ChannelSenderRegistry senderRegistry;
    @Mock private NotifyCrypto crypto;
    @Mock private NotifyMessageFactory messageFactory;
    @Mock private NotifyRateLimiter rateLimiter;
    @Mock private ObjectMapper objectMapper;
    @Mock private Executor executor;

    /*
     * 运行事件与它的推送通道：applyResult 在投递成功或判死时会经它们记一条、推一条。
     *
     * **这两个 mock 是必需的**：@InjectMocks 挑选那个参数最多的构造器，但只会注入
     * 「有对应 @Mock 的」参数 —— 缺了它们，字段就是 null，而生产里那两个由 Spring 注入、
     * 从来不会是 null。漏掉的表现是这两个用例 NPE（CI 上正是这样挂的），
     * 而不是某个断言失败 —— 因为它们压根没走到断言。
     */
    @Mock private AdminEventBroadcaster adminEvents;
    @Mock private EventLogService eventLogService;

    /**
     * 告警入队口。渠道被自动停用时会经它叫一声（见 {@code disableChannel}）——
     * 与上面那两个 mock 同一个理由：@InjectMocks 只会注入「有对应 @Mock 的」参数，
     * 缺了它就是 null，而生产里它由 Spring 注入、从来不会是 null。
     */
    @Mock private AlertOutbox alertOutbox;

    /**
     * 分类器用**真实实现**（无依赖、纯逻辑）：它决定了失败该重试还是判死，
     * 桩掉就等于把这块行为从测试里挖空了。
     */
    @Spy
    private NotifyErrorClassifier errorClassifier = new NotifyErrorClassifier();

    @InjectMocks
    private NotifyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // 阈值给个正常值。不给的话 mock 返回 0，而 consecutiveFailures >= 0 恒真 ——
        // 任何一次失败都会「达到阈值、自动停用渠道」，失败路径的用例就在测一个
        // 现场不会出现的行为。
        when(sysConfigService.getInt(SysConfigKey.NOTIFY_FAILURE_THRESHOLD)).thenReturn(10);

        // NotifyProperties 是 mock，它的嵌套对象默认是 null（退避参数就在里面）。
        // 用真实对象，让退避走真逻辑而不是一堆 0。
        when(properties.getDispatcher()).thenReturn(new NotifyProperties.Dispatcher());
    }

    private NotifyChannel channel() {
        NotifyChannel channel = new NotifyChannel();
        channel.setId(1L);
        channel.setName("飞书");
        channel.setMaxRetries(3);
        channel.setConsecutiveFailures(2);
        return channel;
    }

    private NotifyDelivery delivery() {
        NotifyDelivery delivery = new NotifyDelivery();
        delivery.setId(1L);
        delivery.setSmsMessageId(42L);
        delivery.setChannelId(1L);
        delivery.setAttempts(1);
        return delivery;
    }

    @Test
    @DisplayName("成功后记录里不能留失败原因 —— 否则界面变成「状态成功、却挂着一句报错」")
    void successLeavesNoErrorMessage() {
        NotifyDelivery delivery = delivery();
        // 模拟「第一次失败、重试成功」：进来时还挂着上一轮的错误
        delivery.setLastError("HTTP 500：boom");

        // 飞书的真实成功响应体 —— 注意 describe() 对它会拼出「HTTP 200：{...}」
        SendResult ok = SendResult.ok(200, "{\"StatusCode\":0,\"code\":0,\"msg\":\"success\"}");

        dispatcher.applyResult(channel(), delivery, ok);

        assertThat(delivery.getStatus()).isEqualTo(NotifyDeliveryStatus.SUCCESS);
        assertThat(delivery.getLastError())
                .as("成功之后那条旧错误已经不成立了，留着会让这条记录看起来像失败")
                .isNull();
        assertThat(delivery.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("成功后清掉渠道的连续失败计数 —— 否则它下一次失败就到阈值被自动停用")
    void successResetsChannelHealth() {
        NotifyChannel channel = channel();

        dispatcher.applyResult(channel, delivery(),
                SendResult.ok(200, "{\"code\":0,\"msg\":\"success\"}"));

        assertThat(channel.getConsecutiveFailures()).isZero();
        assertThat(channel.getLastError()).isNull();
        assertThat(channel.getLastSuccessAt()).isNotNull();
    }

    @Test
    @DisplayName("失败时如实写下原因与对端状态码")
    void failureRecordsReason() {
        NotifyDelivery delivery = delivery();

        dispatcher.applyResult(channel(), delivery, SendResult.failed(500, "internal error"));

        assertThat(delivery.getLastError()).contains("500").contains("internal error");
        assertThat(delivery.getResponseCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("请求根本没发出去时不留状态码 —— 0 是个会误导人的「状态码」")
    void networkFailureHasNoStatusCode() {
        NotifyDelivery delivery = delivery();

        dispatcher.applyResult(channel(), delivery,
                SendResult.networkError("ConnectException: Connection refused"));

        assertThat(delivery.getResponseCode()).isNull();
        assertThat(delivery.getLastError()).contains("Connection refused");
    }

    // ------------------------------------------------------------------ 告警载荷

    private NotifyDelivery alertDelivery() {
        NotifyDelivery delivery = NotifyDelivery.alert(
                AlertType.DEVICE_OFFLINE, "device:android-abc", "设备「车间手机」已离线 32 分钟", 1L);
        delivery.setId(7L);
        return delivery;
    }

    @Test
    @DisplayName("告警投递**不去碰** sms_message —— 它的 smsMessageId 是 null")
    void alertDeliveryNeverTouchesSmsRepository() {
        // 这一条守的是一个会**静默降级**的 bug：不去分支的话，findById(null) 会直接抛
        // InvalidDataAccessApiUsageException，掉进通用 catch 被当成「没到达对端、可重试」，
        // 于是同一条告警一直重试到判死 —— 而日志里只有一句「未预期异常」。
        when(senderRegistry.get(any())).thenReturn(null); // 只关心有没有走到取正文那一步

        NotifyDelivery delivery = alertDelivery();
        dispatcher.sendOne(channel(), delivery);

        verify(smsMessageRepository, never()).findById(any());
        // 发送实现不存在 → 判死，而不是重试
        assertThat(delivery.getStatus()).isEqualTo(NotifyDeliveryStatus.DEAD);
    }

    @Test
    @DisplayName("告警走到终点记一条 ALERT_DELIVERY_DEAD")
    void alertDeadRecordsErrorEvent() {
        NotifyDelivery delivery = alertDelivery();

        // 401 在分类器里是终态（配置错，重试无意义）
        dispatcher.applyResult(channel(), delivery, SendResult.failed(401, "unauthorized"));

        assertThat(delivery.getStatus()).isEqualTo(NotifyDeliveryStatus.DEAD);
        verify(eventLogService).record(eq(EventType.ALERT_DELIVERY_DEAD), anyString());
    }

    @Test
    @DisplayName("短信走到终点**不**记 ALERT_DELIVERY_DEAD —— 那是常态，记它只会把事件表刷满")
    void smsDeadDoesNotRecordAlertEvent() {
        NotifyDelivery delivery = delivery();

        dispatcher.applyResult(channel(), delivery, SendResult.failed(401, "unauthorized"));

        assertThat(delivery.getStatus()).isEqualTo(NotifyDeliveryStatus.DEAD);
        verify(eventLogService, never()).record(eq(EventType.ALERT_DELIVERY_DEAD), anyString());
    }
}
