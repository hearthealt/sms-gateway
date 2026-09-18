package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.RecoveryCodeView;
import com.smsgateway.model.dto.StatsView;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDeviceService {

    private final DeviceRepository deviceRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final DeviceService deviceService;

    public static final String STATUS_DISABLED = "DISABLED";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 签发一张恢复码：轮换该设备的重注册密钥，并把**明文**返回一次。
     *
     * <p>用途有两个：设备重装后丢了本地密钥；以及本次变更之前注册的老设备，
     * 它们本来就没有密钥，不签一张就永远无法重新注册。
     *
     * <p>这是全局唯一能把明文交出来的地方 —— 之后服务端只剩 SHA-256。
     * 管理员没记下也不要紧，重新签一张即可，旧密钥随之作废。
     *
     * <p>只有管理员能调：设备自行生成密钥那条路走不通了才用到它，
     * 而能调这个接口的人本来就有这套系统的完全权限。
     */
    public RecoveryCodeView issueRecoveryCode(String deviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));

        String secret = randomSecret();
        device.setEnrollSecretHash(HashUtil.sha256(secret));
        deviceRepository.save(device);

        log.warn("已为设备 {} 签发恢复码，其重注册密钥被轮换，旧密钥立即失效", deviceId);
        return new RecoveryCodeView(deviceId, secret);
    }

    /** 32 字节随机 → base64url（43 字符）：足够抗爆破，也便于人工转述。 */
    private String randomSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public PageResult<DeviceView> list(int page, int pageSize, String deviceId, String phone) {
        Page<SmsDevice> result = deviceRepository.search(
                blankToNull(deviceId), blankToNull(phone), PageRequest.of(page - 1, pageSize));

        List<DeviceView> records = result.getContent().stream()
                .map(this::toView)
                .collect(Collectors.toList());

        return PageResult.of(records, result.getTotalElements(), page, pageSize);
    }

    public DeviceView detail(String deviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        return toView(device);
    }

    public StatsView stats() {
        LocalDateTime since = DeviceService.onlineSince();
        long online = deviceRepository.countOnlineSince(since);
        long offline = deviceRepository.countOfflineSince(since);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        long todaySms = smsMessageRepository.countByReceiveTimeBetween(todayStart, now);
        long todayCodes = smsMessageRepository.countByReceiveTimeBetweenAndCodeIsNotNull(todayStart, now);

        // totalDevices 取 online + offline，不含已禁用设备，
        // 这样前端环形图的两个扇区正好拼成整圆。
        return new StatsView(online, offline, online + offline, todaySms, todayCodes);
    }

    /**
     * 删除一台设备，连同它的全部短信记录。
     *
     * <p><b>删短信是有意的，不是顺手。</b>{@code sms_message.device_id} 指向设备，
     * 只删设备会留下一堆查不到设备的孤儿行 —— 列表每条都要去关联设备名，那些行会显示成空，
     * 看着像数据坏了。短信是诊断数据、不是资产，设备既然移出车队，它的历史一并清掉才是预期。
     *
     * <p>不可撤销，控制台那边有二次确认，并且会把「会一起删掉多少条短信」写在确认框里。
     */
    @Transactional
    public int delete(String deviceId) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));

        int removedSms = smsMessageRepository.deleteByDeviceId(device.getId());
        deviceRepository.delete(device);

        log.warn("Admin deleted device {}, together with {} sms rows", deviceId, removedSms);
        return removedSms;
    }

    @Transactional
    public DeviceView setEnabled(String deviceId, boolean enabled) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));

        device.setStatus(enabled ? "ACTIVE" : STATUS_DISABLED);
        deviceRepository.save(device);
        log.info("Device {} {}", deviceId, enabled ? "enabled" : "disabled");

        return toView(device);
    }

    private DeviceView toView(SmsDevice device) {
        boolean disabled = STATUS_DISABLED.equals(device.getStatus());
        String status = disabled
                ? STATUS_DISABLED
                : (deviceService.isOnline(device) ? "online" : "offline");

        DeviceView view = new DeviceView();
        view.setId(device.getId());
        view.setDeviceId(device.getDeviceId());
        view.setDeviceName(device.getDeviceName());
        view.setPhone(device.getPhoneNumber());
        view.setPlatform(device.getPlatform());
        view.setAppVersion(device.getAppVersion());
        view.setStatus(status);
        view.setEnabled(!disabled);
        view.setLastHeartbeat(device.getLastHeartbeatAt());
        view.setBattery(device.getBattery());
        view.setNetwork(device.getNetwork());
        view.setCharging(device.getCharging());
        view.setPendingCount(device.getPendingCount());
        view.setCreateTime(device.getCreatedAt());
        return view;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
