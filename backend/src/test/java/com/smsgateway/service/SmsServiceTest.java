package com.smsgateway.service;

import com.smsgateway.model.dto.SmsReceiveRequest;
import com.smsgateway.model.dto.SmsReceiveResponse;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 SmsService 接收流程里与规则接入、验证码提取相关的行为。
 * 纯单元测试：不启动 Spring 上下文，不需要 MySQL/Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsServiceTest {

    private static final String PHONE = "13800138000";
    private static final String SENDER = "10690300";
    /** 拦截器认证出的设备身份。测试里直接把它传给 service —— 请求体里的 deviceId 现在会被忽略。 */
    private static final String DEVICE_ID = "android-1";
    // 外部调用方按号码取短信、不看发送方，所以缓存 key 里只有归一化后的号码，没有 sender
    private static final String CODE_KEY = "sms:code:" + PHONE;
    // 频道名仍保留 {phone}:{sender}：订阅方是全量模式订阅、靠消息体匹配的
    private static final String CHANNEL = "sms:channel:" + PHONE + ":" + SENDER;

    @Mock
    private SmsMessageRepository smsMessageRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private CollectRuleEngine collectRuleEngine;

    @InjectMocks
    private SmsService smsService;

    @BeforeEach
    void setUp() {
        SmsDevice device = new SmsDevice();
        device.setId(1L);
        device.setDeviceId("android-1");

        when(deviceRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(device));
        when(smsMessageRepository.findByDeviceIdAndLocalMessageId(1L, "local-1")).thenReturn(Optional.empty());
        when(smsMessageRepository.findBySourceHash(anyString())).thenReturn(Optional.empty());
        when(smsMessageRepository.save(any(SmsMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(collectRuleEngine.decide(anyString(), anyString()))
                .thenReturn(new CollectRuleEngine.Decision(false, null));
    }

    private SmsReceiveRequest request(String content, String clientCode) {
        return request(PHONE, content, clientCode);
    }

    private SmsReceiveRequest request(String phone, String content, String clientCode) {
        SmsReceiveRequest request = new SmsReceiveRequest();
        request.setDeviceId("android-1");
        request.setLocalMessageId("local-1");
        request.setPhone(phone);
        request.setSender(SENDER);
        request.setContent(content);
        request.setCode(clientCode);
        return request;
    }

    @Test
    @DisplayName("设备上报带 +86 的号码时缓存 key 归一化成纯号，外部调用方才能按号等到")
    void normalizesPhoneInCacheKey() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("+8613800138000", "您的验证码是123456，5分钟内有效", null), null);

        assertThat(response.getSmsCode()).isEqualTo("123456");
        verify(valueOps).set(eq(CODE_KEY), eq("123456"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("解析出验证码时写入 Redis 缓存并推送 pub/sub")
    void extractsCodeAndCaches() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("您的验证码是123456，5分钟内有效", null), null);

        assertThat(response.getSmsCode()).isEqualTo("123456");
        verify(valueOps).set(eq(CODE_KEY), eq("123456"), eq(300L), eq(TimeUnit.SECONDS));
        verify(redisTemplate).convertAndSend(eq(CHANNEL), anyString());
    }

    @Test
    @DisplayName("验证码在关键词之前也能提取（此前完全漏掉）")
    void extractsCodeBeforeKeyword() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("【抖音】123456 是您的验证码，请勿泄露", null), null);

        assertThat(response.getSmsCode()).isEqualTo("123456");
    }

    @Test
    @DisplayName("无验证码时不写 Redis：空串会覆盖上一条真实验证码，导致等待方超时")
    void doesNotOverwriteCachedCodeWithEmpty() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("您的订单已发货，请留意签收", null), null);

        assertThat(response.getSmsCode()).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("裸 6 位数字（订单号/快递单号）不再被误判为验证码")
    void plainSixDigitNumberIsNotACode() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("您的订单号 123456 已发货", null), null);

        assertThat(response.getSmsCode()).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("客户端已解析出验证码时优先采用客户端结果")
    void clientProvidedCodeWins() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("随便什么内容", "999999"), null);

        assertThat(response.getSmsCode()).isEqualTo("999999");
        verify(valueOps).set(eq(CODE_KEY), eq("999999"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("客户端传空串时回退到后端解析（空串不等于 null，不能直接采用）")
    void blankClientCodeFallsBackToParsing() {
        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("您的验证码是123456", ""), null);

        assertThat(response.getSmsCode()).isEqualTo("123456");
        verify(valueOps).set(eq(CODE_KEY), eq("123456"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("命中 ignore 规则的短信入库为 IGNORED，但不写缓存、不推送")
    void ignoredSmsIsStoredButNotCachedNorPublished() {
        when(collectRuleEngine.decide(anyString(), anyString()))
                .thenReturn(new CollectRuleEngine.Decision(true, "营销类忽略"));

        SmsReceiveResponse response = smsService.receiveSms(DEVICE_ID,
                request("验证码123456，退订回T", null), null);

        ArgumentCaptor<SmsMessage> saved = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsMessageRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SmsStatus.IGNORED);

        assertThat(response.getStatus()).isEqualTo(SmsStatus.IGNORED.name());
        assertThat(response.getSmsCode()).isNull();
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("未命中任何规则时入库为 RECEIVED（默认采集）")
    void defaultCollectStoresAsReceived() {
        smsService.receiveSms(DEVICE_ID, request("您的验证码是123456", null), null);

        ArgumentCaptor<SmsMessage> saved = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsMessageRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SmsStatus.RECEIVED);
    }

    @Test
    @DisplayName("身份以认证结果为准，请求体里的 deviceId 被忽略（防越权写他人设备）")
    void ignoresDeviceIdFromRequestBody() {
        SmsReceiveRequest req = request("您的验证码是123456", null);
        req.setDeviceId("someone-else");     // 拿自己的令牌冒充他人设备

        smsService.receiveSms(DEVICE_ID, req, null);

        ArgumentCaptor<SmsMessage> saved = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsMessageRepository).save(saved.capture());
        // 落库的是认证设备的 PK（stub 里 id=1），冒充的那个 deviceId 从头到尾没被查过
        assertThat(saved.getValue().getDeviceId()).isEqualTo(1L);
        verify(deviceRepository, never()).findByDeviceId("someone-else");
    }

    @Test
    @DisplayName("内容重复时只回报重复，不再插入与唯一索引冲突的第二行")
    void duplicateDoesNotInsertConflictingRow() {
        SmsMessage original = new SmsMessage();
        original.setId(99L);
        original.setStatus(SmsStatus.RECEIVED);
        // 已存在同内容的行 —— 原实现正是在这里拿同一个 hash 再插一行，撞上 uk_source_hash
        // 抛 DataIntegrityViolationException、被兜成 500，设备端于是无限重试。
        // 那条 INSERT 100% 失败，所以这个分支从来没被跑到过：旧测试把查询恒桩成了 empty。
        when(smsMessageRepository.findBySourceHash(anyString())).thenReturn(Optional.of(original));

        SmsReceiveResponse response = smsService.receiveSms(
                DEVICE_ID, request("您的验证码是123456", null), null);

        assertThat(response.isDuplicate()).isTrue();
        assertThat(response.getMessageId()).isEqualTo(99L);
        verify(smsMessageRepository, never()).save(any(SmsMessage.class));
    }
}
