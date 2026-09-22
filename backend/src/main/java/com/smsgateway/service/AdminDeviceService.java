package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.RecoveryCodeView;
import com.smsgateway.model.dto.StatsView;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.EventLogRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.HashUtil;
import com.smsgateway.util.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDeviceService {

    private final DeviceRepository deviceRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final NotifyDeliveryRepository notifyDeliveryRepository;
    private final DeviceService deviceService;
    private final EventLogRepository eventLogRepository;
    private final EventLogService eventLogService;

    public static final String STATUS_DISABLED = "DISABLED";

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

        String secret = SecretGenerator.randomSecret();
        device.setEnrollSecretHash(HashUtil.sha256(secret));
        deviceRepository.save(device);

        log.warn("已为设备 {} 签发恢复码，其重注册密钥被轮换，旧密钥立即失效", deviceId);
        return new RecoveryCodeView(deviceId, secret);
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

        // 顺序不能反，投递记录必须赶在短信前面删。
        //
        // 它只存 sms_message_id、不自带 device_id，而那张表上**没有外键**（建的只是普通
        // 索引），所以删短信既不会被挡住、也不会连带删。短信一没，这些投递记录就再也
        // 认不回属于谁 —— 它们会一直挂在「投递记录」页上，渲染成一行发送方是「-」、
        // 内容是空白的孤儿（见 NotifyDeliveryService.toView）。
        int removedDeliveries = notifyDeliveryRepository.deleteByDeviceId(device.getId());
        int removedSms = smsMessageRepository.deleteByDeviceId(device.getId());

        // 这台设备的历史事件一并清掉，与上面两条同一个道理：设备既然移出车队，
        // 它的运行记录也不该继续占着列表。但**「删除」这个动作本身要留痕**，
        // 所以下面在事务提交之后再补记一条。
        eventLogRepository.deleteByDeviceId(device.getId());

        deviceRepository.delete(device);

        // 只带 device_code、不带主键：提交之后 sms_device 里的行已经没了，
        // 再写主键就是一个永远悬空的外键。
        eventLogService.recordAfterCommit(EventType.DEVICE_DELETED, deviceId, "管理员删除设备");

        log.warn("Admin deleted device {}, together with {} sms rows and {} notify deliveries",
                deviceId, removedSms, removedDeliveries);
        return removedSms;
    }

    @Transactional
    public DeviceView setEnabled(String deviceId, boolean enabled) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));

        device.setStatus(enabled ? "ACTIVE" : STATUS_DISABLED);
        deviceRepository.save(device);

        // 「谁把设备停了」是现场最常问的一句话，而它原先在服务端一点痕迹都没有 ——
        // 设备端只看到上传开始被 403，界面显示「已被管理员禁用」，看不出是谁什么时候做的。
        eventLogService.recordAfterCommit(
                enabled ? EventType.DEVICE_ENABLED_BY_ADMIN : EventType.DEVICE_DISABLED_BY_ADMIN,
                device,
                enabled ? "管理员启用设备" : "管理员禁用设备");

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
