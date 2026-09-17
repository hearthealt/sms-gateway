package com.smsgateway.service;

import com.smsgateway.model.dto.DeviceView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.StatsView;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
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
    private final DeviceService deviceService;

    public static final String STATUS_DISABLED = "DISABLED";

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
