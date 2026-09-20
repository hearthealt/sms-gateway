package com.smsgateway.service.notify;

import com.smsgateway.model.enums.NotifyChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道类型 → 发送实现的分派表。
 *
 * <p>让调度器只认 {@link ChannelSender} 这一个接口，加渠道时不必回头改调度逻辑 ——
 * 那是最容易出现「加了渠道忘了改 if-else」的地方。
 *
 * <p>启动时会检查「枚举里的每个类型都有实现」。少一个的话，它要到真正有短信
 * 要转发时才暴露成「渠道不存在」，而那一刻配置的人早就离开了。
 */
@Slf4j
@Component
public class ChannelSenderRegistry {

    private final Map<NotifyChannelType, ChannelSender> senders = new EnumMap<>(NotifyChannelType.class);

    public ChannelSenderRegistry(List<ChannelSender> discovered) {
        for (ChannelSender sender : discovered) {
            ChannelSender existing = senders.put(sender.type(), sender);
            if (existing != null) {
                throw new IllegalStateException(
                        "渠道类型 " + sender.type() + " 有两个实现："
                                + existing.getClass().getSimpleName() + " 与 " + sender.getClass().getSimpleName());
            }
        }

        for (NotifyChannelType type : NotifyChannelType.values()) {
            if (!senders.containsKey(type)) {
                throw new IllegalStateException(
                        "渠道类型 " + type + " 没有对应的 ChannelSender 实现 —— "
                                + "枚举里加了值就必须同时提供实现，否则该类型的渠道配得出来但发不出去。");
            }
        }
    }

    /** @return 该类型的发送实现；类型未实现时返回 null（调用方按终态失败处理）。 */
    public ChannelSender get(NotifyChannelType type) {
        return senders.get(type);
    }
}
