package com.smsgateway.service;

import com.smsgateway.exception.EnrollmentRequiredException;
import com.smsgateway.model.dto.DeviceRegisterRequest;
import com.smsgateway.model.dto.DeviceRegisterResponse;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.HashUtil;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册与重注册的校验。
 *
 * <p>这组测试守的是一条具体的攻击路径：注册接口必须免鉴权（设备得先能注册才拿得到令牌），
 * 而对**已存在**的 deviceId，原实现会把令牌原样返还给任何知道这个 deviceId 的人 ——
 * 而设备号在管理后台列表、设备端界面、任何截图里都可见。
 *
 * <p>另一条守的是并发兜底路径：那里若不做校验，就能靠「并发发两条、其中一条用错的密钥」
 * 绕过检查拿到令牌。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceServiceTest {

    private static final String DEVICE_ID = "android-abc";
    private static final String SECRET = "s3cret-value";
    private static final String SECRET_KEY = "test-secret-key";

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private SmsMessageRepository smsMessageRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(deviceService, "secretKey", SECRET_KEY);

        // 让编程式事务直接执行回调，不真的开事务
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(deviceRepository.save(any(SmsDevice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DeviceRegisterRequest request(String secret) {
        DeviceRegisterRequest req = new DeviceRegisterRequest();
        req.setDeviceId(DEVICE_ID);
        req.setEnrollSecret(secret);
        req.setDeviceName("测试机");
        req.setPlatform("android");
        req.setAppVersion("1.0.0");
        return req;
    }

    private SmsDevice existingDevice(String enrollSecretHash) {
        SmsDevice device = new SmsDevice();
        device.setId(1L);
        device.setDeviceId(DEVICE_ID);
        device.setDeviceToken("old-token");
        device.setStatus("ACTIVE");
        device.setEnrollSecretHash(enrollSecretHash);
        return device;
    }

    @Test
    @DisplayName("新设备注册时记下密钥哈希，并返回令牌")
    void newDeviceStoresSecretHash() {
        when(deviceRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        DeviceRegisterResponse response = deviceService.register(request(SECRET));

        ArgumentCaptor<SmsDevice> saved = ArgumentCaptor.forClass(SmsDevice.class);
        verify(deviceRepository).save(saved.capture());
        // 存的是哈希，不是明文
        assertThat(saved.getValue().getEnrollSecretHash()).isEqualTo(HashUtil.sha256(SECRET));
        assertThat(saved.getValue().getEnrollSecretHash()).isNotEqualTo(SECRET);
        assertThat(response.getDeviceToken()).isEqualTo(HashUtil.hmacSha256(DEVICE_ID, SECRET_KEY));
    }

    @Test
    @DisplayName("已存在的设备带对密钥时，正常返还令牌")
    void reRegisterWithCorrectSecretSucceeds() {
        when(deviceRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(existingDevice(HashUtil.sha256(SECRET))));

        DeviceRegisterResponse response = deviceService.register(request(SECRET));

        assertThat(response.getDeviceToken()).isEqualTo(HashUtil.hmacSha256(DEVICE_ID, SECRET_KEY));
        assertThat(response.getDeviceId()).isEqualTo(DEVICE_ID);
    }

    @Test
    @DisplayName("已存在的设备带错密钥时拒绝，且不返回令牌")
    void reRegisterWithWrongSecretIsRejected() {
        when(deviceRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(existingDevice(HashUtil.sha256(SECRET))));

        assertThatThrownBy(() -> deviceService.register(request("wrong-secret")))
                .isInstanceOf(EnrollmentRequiredException.class);
    }

    @Test
    @DisplayName("设备重装丢了密钥（带空）时拒绝 —— 这正是原先能凭设备号拿令牌的路径")
    void reRegisterWithoutSecretIsRejected() {
        when(deviceRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(existingDevice(HashUtil.sha256(SECRET))));

        assertThatThrownBy(() -> deviceService.register(request("")))
                .isInstanceOf(EnrollmentRequiredException.class);
    }

    @Test
    @DisplayName("新设备不带密钥时拒绝建档 —— 否则这台设备以后永远无法重新注册")
    void newDeviceWithoutSecretIsRejected() {
        when(deviceRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.register(request(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enrollSecret");
        verify(deviceRepository, never()).save(any(SmsDevice.class));
    }

    @Test
    @DisplayName("本次变更前注册的老设备（没有密钥哈希）拒绝，提示去签恢复码")
    void legacyDeviceWithoutSecretIsRejected() {
        when(deviceRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(existingDevice(null)));

        assertThatThrownBy(() -> deviceService.register(request(SECRET)))
                .isInstanceOf(EnrollmentRequiredException.class)
                .hasMessageContaining("恢复码");
        // 校验失败必须整体拒绝，不能留下半截修改
        verify(deviceRepository, never()).save(any(SmsDevice.class));
    }
}
