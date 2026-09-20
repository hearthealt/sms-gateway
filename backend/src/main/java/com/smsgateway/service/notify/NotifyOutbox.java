package com.smsgateway.service.notify;

import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import com.smsgateway.model.entity.NotifyDelivery;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.repository.NotifyDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 事务性发件箱的写入侧。
 *
 * <p><b>必须在 {@code SmsService} 的接收事务内调用。</b>这一行的 INSERT 与
 * sms_message 的 INSERT 在同一个事务里：事务提交则投递任务必然存在，回滚则两者都不存在。
 * 这就是「不存在『短信存了但没转发』的中间态」的全部实现 ——
 * 也是这个功能必须做在服务端的根本理由，放在设备上就没有事务可依附。
 *
 * <p>调用点刻意放在**保存 sms_message 之后**、并且用 {@code message.getId()}：
 * 主键由 JPA 回填，拿到它才建得出关联。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyOutbox {

    private final SysConfigService sysConfigService;
    private final NotifyRouteEngine routeEngine;
    private final NotifyDeliveryRepository deliveryRepository;

    /**
     * 按路由规则为这条短信建投递任务。
     *
     * <p>不抛异常：转发是旁路能力，它出问题不该让**短信上报**失败 ——
     * 设备那边收到 500 会一直重试这条短信，而短信本身早就存好了。
     * 路由匹配用的是内存里的规则表，正常也不会失败。
     */
    public void enqueue(SmsDevice device, SmsMessage message) {
        // 总开关在库里（不是 yml），所以这里是**每次上报都读一次**——
        // SysConfigService 带进程内缓存，实际不打数据库
        if (!sysConfigService.getBoolean(SysConfigKey.NOTIFY_ENABLED)) {
            return;
        }

        try {
            Set<Long> channelIds = routeEngine.matchChannels(device, message);
            if (channelIds.isEmpty()) {
                return;
            }

            List<NotifyDelivery> deliveries = channelIds.stream()
                    .map(channelId -> NotifyDelivery.pending(message.getId(), channelId))
                    .toList();
            deliveryRepository.saveAll(deliveries);

            log.debug("转发任务已入队：smsMessageId={}, channels={}", message.getId(), channelIds);
        } catch (Exception e) {
            // 吞掉但记日志：见方法注释。这里如果抛出去，一条「路由规则写坏了」
            // 会让**所有短信上报**开始失败，而短信本身跟转发规则毫无关系。
            log.error("建立转发任务失败，本条短信不会被转发：smsMessageId={}", message.getId(), e);
        }
    }
}
