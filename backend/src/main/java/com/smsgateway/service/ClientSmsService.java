package com.smsgateway.service;

import com.smsgateway.model.dto.ClientSmsView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.SmsStatus;
import com.smsgateway.repository.SmsMessageRepository;
import com.smsgateway.util.PhoneUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 外部调用方的短信查询。
 *
 * <p>条件全部可选，都不传就是「取全部」—— 这正是
 * {@link SmsMessageRepository#search} 的语义，所以直接复用，不另写查询。
 * sender / code / deviceId 传 null 表示不按这些维度过滤（外部方只关心号码和时间）。
 *
 * <p>口径与管理后台列表一致：默认排除命中 ignore 规则的短信（营销类噪声）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientSmsService {

    private final SmsMessageRepository smsMessageRepository;

    public PageResult<ClientSmsView> list(String phone, LocalDateTime startTime, LocalDateTime endTime,
                                          int page, int pageSize) {
        Page<SmsMessage> result = smsMessageRepository.search(
                phoneFilter(phone), null, null,
                startTime, endTime, false, SmsStatus.IGNORED,
                PageRequest.of(page - 1, pageSize));

        List<ClientSmsView> views = result.getContent().stream()
                .map(ClientSmsService::toView)
                .toList();

        return PageResult.of(views, result.getTotalElements(), page, pageSize);
    }

    /**
     * 归一化后再交给 like 查询。
     *
     * <p>查询用的是 {@code like concat('%', :phone, '%')}，所以传 13800138000
     * 能同时命中库里存的 13800138000 和 +8613800138000 —— 历史数据不用迁移。
     * 反过来若直接把调用方传的 +86 原样送进去，就匹配不上库里存纯号的行。
     */
    private static String phoneFilter(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String normalized = PhoneUtil.normalize(phone);
        return normalized.isEmpty() ? null : normalized;
    }

    private static ClientSmsView toView(SmsMessage msg) {
        return new ClientSmsView(
                msg.getId(),
                msg.getPhone(),
                msg.getSender(),
                msg.getContent(),
                msg.getCode(),
                msg.getReceiveTime());
    }
}
