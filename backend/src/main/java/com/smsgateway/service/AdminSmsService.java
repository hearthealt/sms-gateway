package com.smsgateway.service;

import com.smsgateway.model.dto.DailyCount;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.SmsView;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.DeviceRepository;
import com.smsgateway.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSmsService {

    private final SmsMessageRepository smsMessageRepository;
    private final DeviceRepository deviceRepository;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 短信分页查询。includeIgnored 为 false 时排除命中 ignore 规则的短信，
     * 保证默认视图只看到真正采集下来的内容。
     *
     * <p>不支持按发送方筛选：那个搜索框已从界面上移除，保留参数只会成为无人使用的死代码。
     */
    public PageResult<SmsView> list(int page, int pageSize, String phone, String code,
                                    LocalDateTime startTime, LocalDateTime endTime, String deviceId,
                                    boolean includeIgnored) {

        Long devicePk = null;
        if (deviceId != null && !deviceId.isBlank()) {
            SmsDevice device = deviceRepository.findByDeviceId(deviceId).orElse(null);
            if (device == null) {
                // 设备不存在时返回空结果，而不是把 deviceId 当数字主键去查
                return PageResult.of(List.of(), 0, page, pageSize);
            }
            devicePk = device.getId();
        }

        Page<SmsMessage> result = smsMessageRepository.search(
                blankToNull(phone), blankToNull(code), devicePk,
                startTime, endTime, includeIgnored, SmsStatus.IGNORED,
                PageRequest.of(page - 1, pageSize));

        return PageResult.of(toViews(result.getContent()), result.getTotalElements(), page, pageSize);
    }

    /**
     * 某台设备的短信记录。
     *
     * @param keyword 关键词，命中**发送方或正文**任一即可；空表示不过滤。
     *                在服务端筛而不是让设备端在已加载的几页里筛 —— 设备端是无限滚动的，
     *                前端筛只会给出「明明有却说没有」的结论。
     */
    public PageResult<SmsView> byDevice(String deviceId, int page, int pageSize,
                                        boolean includeIgnored, String keyword) {
        SmsDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));

        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();

        Page<SmsMessage> result = smsMessageRepository.searchByDevice(
                device.getId(), includeIgnored, SmsStatus.IGNORED,
                trimmed == null, trimmed == null ? "" : trimmed,
                PageRequest.of(page - 1, pageSize));

        return PageResult.of(toViews(result.getContent()), result.getTotalElements(), page, pageSize);
    }

    /** 仪表盘近 N 天短信量，含无数据的日期（补 0），保证图表 X 轴连续。 */
    public List<DailyCount> daily(int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);

        Map<String, DailyCount> counts = new HashMap<>();
        for (Object[] row : smsMessageRepository.countDailySince(from.atStartOfDay(), SmsStatus.IGNORED)) {
            if (row[0] == null) continue;
            long total = ((Number) row[1]).longValue();
            long codes = row[2] == null ? 0L : ((Number) row[2]).longValue();
            counts.put(String.valueOf(row[0]), new DailyCount(String.valueOf(row[0]), total, codes));
        }

        List<DailyCount> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            String day = from.plusDays(i).format(DAY_FORMAT);
            DailyCount existing = counts.get(day);
            result.add(existing != null ? existing : new DailyCount(day, 0L, 0L));
        }
        return result;
    }

    /**
     * 批量把实体转视图。设备信息一次性查出来做成映射，
     * 避免逐条短信查询设备导致的 N+1。
     */
    private List<SmsView> toViews(List<SmsMessage> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }

        Set<Long> devicePks = messages.stream().map(SmsMessage::getDeviceId).collect(Collectors.toSet());
        Map<Long, SmsDevice> devices = deviceRepository.findAllById(devicePks).stream()
                .collect(Collectors.toMap(SmsDevice::getId, Function.identity()));

        return messages.stream().map(msg -> {
            SmsDevice device = devices.get(msg.getDeviceId());
            SmsView view = new SmsView();
            view.setId(msg.getId());
            view.setDeviceId(device != null ? device.getDeviceId() : null);
            view.setDeviceName(device != null ? device.getDeviceName() : null);
            view.setPhone(msg.getPhone());
            view.setSender(msg.getSender());
            view.setContent(msg.getContent());
            view.setCode(msg.getCode());
            view.setStatus(msg.getStatus() != null ? msg.getStatus().name() : null);
            view.setReceiveTime(msg.getReceiveTime());
            view.setDuplicateCount(msg.getDuplicateCount());
            view.setUpdatedAt(msg.getUpdatedAt());
            return view;
        }).collect(Collectors.toList());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
