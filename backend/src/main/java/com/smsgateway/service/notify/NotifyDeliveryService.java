package com.smsgateway.service.notify;

import com.smsgateway.model.dto.NotifyDeliveryView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.NotifyDeliverySource;
import com.smsgateway.model.enums.NotifyDeliveryStatus;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.smsgateway.service.AdminEventBroadcaster;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyDeliveryService {

    /** 列表里的正文预览长度。够看清是哪条短信，又不至于把整篇搬上来。 */
    private static final int PREVIEW_LENGTH = 60;

    /**
     * 原短信已被删掉时，正文预览的位置显示这句话。
     *
     * <p>不做成空白：控制台渲染的是 {@code contentPreview || ''}，空白既看不出
     * 「已删除」也看不出「内容为空」，只会被当成数据坏了。
     */
    private static final String SMS_DELETED_PREVIEW = "（原短信已删除）";

    private final NotifyDeliveryRepository deliveryRepository;
    private final SmsMessageRepository smsMessageRepository;
    /**
     * 直接依赖渠道仓储，而不是绕道 {@code NotifyRouteService} 拿渠道名。
     * 投递记录要展示的不只是名字，还有**渠道是否启用** —— 那决定了这条记录
     * 是「排队中」还是「已暂停」，是两回事。
     */
    private final NotifyChannelRepository channelRepository;
    private final AdminEventBroadcaster adminEvents;

    public PageResult<NotifyDeliveryView> list(int page, int pageSize, Long channelId, String status) {
        NotifyDeliveryStatus parsedStatus = parseStatus(status);

        Page<NotifyDelivery> result = deliveryRepository.search(
                channelId, parsedStatus, PageRequest.of(page - 1, pageSize));

        List<NotifyDelivery> rows = result.getContent();

        // 各批量取一次，避免 N+1：一页 20 条时逐个 findById 就是 20 次查询。
        // 告警行的 smsMessageId 是 null，**必须滤掉**再交给 findAllById ——
        // 里面混进 null 会让 Hibernate 直接抛异常，整页投递记录都打不开。
        List<Long> smsIds = rows.stream()
                .map(NotifyDelivery::getSmsMessageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SmsMessage> smsById = smsMessageRepository
                .findAllById(smsIds)
                .stream()
                .collect(Collectors.toMap(SmsMessage::getId, Function.identity(), (a, b) -> a));

        Map<Long, NotifyChannel> channelById = channelRepository.findAll().stream()
                .collect(Collectors.toMap(NotifyChannel::getId, Function.identity(), (a, b) -> a));

        List<NotifyDeliveryView> views = rows.stream()
                .map(row -> toView(row, smsById.get(row.getSmsMessageId()),
                        channelById.get(row.getChannelId())))
                .toList();

        return PageResult.of(views, result.getTotalElements(), page, pageSize);
    }

    /**
     * 手动重投。
     *
     * <p>把记录退回 PENDING 并立即到期，剩下的交给调度器 —— 不在这里直接发。
     * 直接发会绕过限流与 inFlight 串行，正是调度器存在的意义。
     */
    @Transactional
    public NotifyDeliveryView retry(Long id) {
        NotifyDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("投递记录不存在: " + id));

        if (delivery.getStatus() == NotifyDeliveryStatus.SUCCESS) {
            throw new IllegalArgumentException("这条已经投递成功了，重投会重复发送");
        }

        // 渠道不可用就拒绝重投。不挡的话它会变回「待投递」，而调度查询会滤掉停用/
        // 已删除的渠道 —— 于是这条记录又回到「待投递 + 下次重试 13:51」那个状态，
        // 正是「停用渠道时为什么不清掉积压」抱怨的那个样子。
        NotifyChannel channel = channelRepository.findById(delivery.getChannelId()).orElse(null);
        if (channel == null) {
            throw new IllegalArgumentException(
                    "这条记录所属的渠道已被删除，无法重投。请新建渠道并调整转发规则。");
        }
        if (!channel.isEnabled()) {
            throw new IllegalArgumentException(
                    "这条记录所属的渠道「" + channel.getName() + "」已停用。"
                            + "请先在「转发渠道」页启用它，再回来重投 —— 否则它只会变回待投递，发不出去。");
        }

        // 重投是一次新的开始：attempts 归零，否则它一上来就已经用光了重试次数、
        // 下次失败直接判死，等于重投没生效。
        delivery.setStatus(NotifyDeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextRetryAt(LocalDateTime.now());
        delivery.setLastError(null);
        deliveryRepository.save(delivery);

        // 手动重投会让这条记录从「失败」变回「待投递」，正在看投递记录页的人
        // 应该立刻看到这个变化 —— 否则他会以为没点上，再点一次。
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_DELIVERIES,
                Collections.singletonMap("id", id));

        log.info("手动重投：deliveryId={}", id);

        // channel 在上面校验时就查过了，这里直接复用（那时已保证非 null）。
        // 告警行没有关联短信，findById(null) 会抛 —— 必须先判来源。
        SmsMessage sms = delivery.getSmsMessageId() == null
                ? null
                : smsMessageRepository.findById(delivery.getSmsMessageId()).orElse(null);
        return toView(delivery, sms, channel);
    }

    private NotifyDeliveryStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NotifyDeliveryStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的投递状态：" + raw);
        }
    }

    private NotifyDeliveryView toView(NotifyDelivery delivery, SmsMessage sms, NotifyChannel channel) {
        NotifyDeliveryView view = new NotifyDeliveryView();
        view.setId(delivery.getId());
        view.setSmsMessageId(delivery.getSmsMessageId());
        view.setChannelId(delivery.getChannelId());
        view.setChannelName(channel == null ? "（渠道已删除）" : channel.getName());
        // 渠道没了也按「暂停」显示：那条记录确实不会再发出去了
        view.setChannelEnabled(channel != null && channel.isEnabled());
        view.setStatus(delivery.getStatus().name());
        view.setAttempts(delivery.getAttempts());
        view.setNextRetryAt(delivery.getNextRetryAt());
        view.setResponseCode(delivery.getResponseCode());
        view.setLastError(delivery.getLastError());
        view.setSentAt(delivery.getSentAt());
        view.setCreatedAt(delivery.getCreatedAt());
        view.setSourceType(delivery.getSourceType().name());

        // 告警行：正文预览就是那条告警的摘要。
        //
        // **这一支必须判在 `sms == null` 之前。** 告警的 sms 天然为 null，
        // 沿用下面那个判据的话，每一行告警都会显示成「（原短信已删除）」——
        // 而那是一句看起来很确定的谎话，排查时会把人引到完全错误的方向。
        if (delivery.getSourceType() == NotifyDeliverySource.ALERT) {
            view.setAlertTypeLabel(
                    delivery.getAlertType() == null ? null : delivery.getAlertType().label());
            view.setContentPreview(delivery.getAlertSummary());
            return view;
        }

        if (sms != null) {
            view.setSender(sms.getSender());
            view.setPhone(sms.getPhone());
            // 预览就是短信原文的前一段。这里**不再打码** —— 转发出去的本来就是
            // 完整正文，管理端打码只会让人以为转发也打码了。
            // 「投递记录不存渲染后的正文」这条设计仍然成立，理由与打码无关：
            // 存了等于把验证码写两遍，而第二遍没有 TTL。
            view.setContentPreview(NotifyRedactor.truncate(sms.getContent(), PREVIEW_LENGTH));
        } else {
            // 原短信已经不在了。现在删设备的短信时会连带删掉投递记录（见
            // AdminDeviceService.delete），保留策略也一样（SmsRetentionJob），
            // 但**历史上留下的孤儿行还在**，而且这两条路将来也可能漏。
            // 不写这一句的话，这种行渲染出来是「发送方 - 、内容空白」—— 控制台那边
            // 只是 `contentPreview || ''`，分辨不出「已删除」和「内容本来就是空的」，
            // 只能被当成数据坏了。
            view.setContentPreview(SMS_DELETED_PREVIEW);
        }

        return view;
    }
}
