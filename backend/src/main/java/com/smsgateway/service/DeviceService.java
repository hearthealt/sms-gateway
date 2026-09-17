package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceRegisterRequest;
import com.smsgateway.model.dto.DeviceRegisterResponse;
import com.smsgateway.model.dto.DeviceSmsStats;
import com.smsgateway.model.dto.HeartbeatRequest;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 用编程式事务而不是 @Transactional：注册需要「撞唯一约束后重查」的兜底，
     * 而 @Transactional 一旦把事务标记为 rollback-only，同一个事务里后续查询会直接失败。
     * 必须让失败的那次写入彻底回滚后，再另起一次查询。
     */
    private final TransactionTemplate transactionTemplate;

    @Value("${app.secret.key}")
    private String secretKey;

    private static final String DEVICE_ONLINE_KEY_PREFIX = "sms:device:";
    private static final String DEVICE_ONLINE_SUFFIX = ":online";
    private static final long HEARTBEAT_TTL_SECONDS = 90;

    public DeviceRegisterResponse register(DeviceRegisterRequest request) {
        try {
            return transactionTemplate.execute(status -> doRegister(request));
        } catch (DataIntegrityViolationException e) {
            // 两个相同 deviceId 的注册并发到达时，双方都会在查询时扑空，后落地的那个
            // 撞上 device_id 的唯一约束。注册本身是幂等的（token 由 deviceId 的 HMAC 推导，
            // 可重算），所以这里重查一次返回已有设备即可，不必把一个重复请求变成 500。
            log.info("Concurrent registration for deviceId={}, returning existing device",
                    request.getDeviceId());
            return deviceRepository.findByDeviceId(request.getDeviceId())
                    .map(device -> new DeviceRegisterResponse(
                            HashUtil.hmacSha256(device.getDeviceId(), secretKey),
                            device.getDeviceId(),
                            device.getStatus()))
                    .orElseThrow(() -> e);
        }
    }

    private DeviceRegisterResponse doRegister(DeviceRegisterRequest request) {
        String deviceId = request.getDeviceId();

        Optional<SmsDevice> existingDevice = deviceRepository.findByDeviceId(deviceId);
        if (existingDevice.isPresent()) {
            SmsDevice device = existingDevice.get();
            String deviceToken = HashUtil.hmacSha256(deviceId, secretKey);
            device.setDeviceToken(deviceToken);
            if (request.getDeviceName() != null) {
                device.setDeviceName(request.getDeviceName());
            }
            if (request.getPhoneNumber() != null) {
                device.setPhoneNumber(request.getPhoneNumber());
            }
            if (request.getPlatform() != null) {
                device.setPlatform(request.getPlatform());
            }
            if (request.getAppVersion() != null) {
                device.setAppVersion(request.getAppVersion());
            }
            deviceRepository.save(device);
            log.info("Device re-registered: {}", deviceId);
            // 重复注册不重置 status：被管理员禁用的设备重新注册后仍是禁用，
            // 客户端据此显示横幅，而不是因为「注册成功」就以为可以用了。
            return new DeviceRegisterResponse(deviceToken, deviceId, device.getStatus());
        }

        String deviceToken = HashUtil.hmacSha256(deviceId, secretKey);

        SmsDevice device = new SmsDevice();
        device.setDeviceId(deviceId);
        device.setDeviceToken(deviceToken);
        device.setDeviceName(request.getDeviceName());
        device.setPhoneNumber(request.getPhoneNumber());
        device.setPlatform(request.getPlatform());
        device.setAppVersion(request.getAppVersion());
        device.setStatus("ACTIVE");
        deviceRepository.save(device);

        log.info("Device registered: {}", deviceId);
        return new DeviceRegisterResponse(deviceToken, deviceId, device.getStatus());
    }

    /**
     * @return 设备当前状态（ACTIVE / DISABLED），随心跳响应回传给设备。
     *         这是设备得知自己「已被禁用 / 已被恢复」的唯一通道。
     */
    public String heartbeat(HeartbeatRequest request) {
        String deviceId = request.getDeviceId();

        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        device.setLastHeartbeatAt(LocalDateTime.now());
        if (request.getDeviceName() != null) {
            device.setDeviceName(request.getDeviceName());
        }
        if (request.getPhoneNumber() != null) {
            device.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getBattery() != null) {
            device.setBattery(request.getBattery());
        }
        if (request.getNetwork() != null) {
            device.setNetwork(request.getNetwork());
        }
        if (request.getCharging() != null) {
            device.setCharging(request.getCharging());
        }
        if (request.getPendingCount() != null) {
            device.setPendingCount(request.getPendingCount());
        }
        deviceRepository.save(device);

        // Update Redis online status
        String redisKey = DEVICE_ONLINE_KEY_PREFIX + deviceId + DEVICE_ONLINE_SUFFIX;
        redisTemplate.opsForValue().set(redisKey, LocalDateTime.now().toString(), HEARTBEAT_TTL_SECONDS, TimeUnit.SECONDS);

        log.debug("Heartbeat received from device: {}", deviceId);
        return device.getStatus();
    }

    /**
     * 设备今日的短信统计。
     *
     * 由服务端算而不是让设备统计本地库：设备端的记录页展示的就是服务端数据，
     * 同源才不会出现「显示 0、点进去却有内容」。设备本地库会因为清理历史、
     * 清除应用数据等原因与服务端不一致。
     */
    public DeviceSmsStats todayStats(String deviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        return new DeviceSmsStats(
                smsMessageRepository.countByDeviceAndReceiveTimeBetween(device.getId(), start, end),
                smsMessageRepository.countCodesByDeviceAndReceiveTimeBetween(device.getId(), start, end));
    }

    public SmsDevice getDeviceByToken(String token) {
        return deviceRepository.findByDeviceToken(token)
                .orElse(null);
    }

    public SmsDevice getDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .orElse(null);
    }

    /**
     * 设备是否在线：最后一次心跳距今是否在 90 秒内。
     * 与 {@link #heartbeat} 写入的 Redis key TTL 同源（都起于心跳时刻），
     * 但用时间戳判断可以避免统计时对每台设备各做一次 Redis 往返。
     */
    public boolean isOnline(SmsDevice device) {
        return device.getLastHeartbeatAt() != null
                && device.getLastHeartbeatAt().isAfter(LocalDateTime.now().minusSeconds(HEARTBEAT_TTL_SECONDS));
    }

    /** 在线判定所用的时间窗起点，供仓储层统计查询复用。 */
    public static LocalDateTime onlineSince() {
        return LocalDateTime.now().minusSeconds(HEARTBEAT_TTL_SECONDS);
    }
}