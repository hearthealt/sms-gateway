package com.smsgateway.service.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsgateway.model.dto.NotifyChannelRequest;
import com.smsgateway.model.dto.NotifyChannelView;
import com.smsgateway.model.dto.NotifyTestResult;
import com.smsgateway.model.entity.NotifyChannel;
import com.smsgateway.model.entity.NotifyRoute;
import com.smsgateway.model.entity.SmsMessage;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.repository.NotifyChannelRepository;
import com.smsgateway.repository.NotifyDeliveryRepository;
import com.smsgateway.repository.NotifyRouteRepository;
import com.smsgateway.service.AdminEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 转发渠道的管理端逻辑：增删改查 + 测试发送。
 *
 * <p>与 {@code ApiKeyService} 最大的区别是**凭据加密**：这里每次写库都要加密、
 * 每次读库都要解密，而回显给管理端的是打码值。三条规矩不能破：
 * 回显打码、提交留空即不改、错误摘要脱敏（{@link NotifyRedactor}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyChannelService {

    private static final int PREVIEW_LENGTH = 200;

    private final NotifyChannelRepository channelRepository;
    private final AdminEventBroadcaster adminEvents;

    /**
     * 渠道页的「该刷新了」信号。
     *
     * <p>这一页**真的有服务端自变源**：渠道连续失败到阈值会被自动停用（见
     * {@code NotifyDispatcher}），那不是任何人的操作，不推的话管理员看不到 ——
     * 而这恰恰是最该立刻知道的一件事（渠道挂了，验证码就转发不出去了）。
     *
     * <p>但**不要在每次失败时推**：投递失败是常态（对端抖动），逐次推等于刷屏。
     * 只在状态真的变了的地方推（增删改、自动停用）。
     */
    private void notifyChanged() {
        adminEvents.broadcast(AdminEventBroadcaster.EVENT_CHANNELS, Map.of());
    }
    private final NotifyRouteRepository routeRepository;
    private final NotifyDeliveryRepository deliveryRepository;
    private final NotifyCrypto crypto;
    private final ChannelSenderRegistry senderRegistry;
    private final NotifyMessageFactory messageFactory;
    private final ObjectMapper objectMapper;

    public List<NotifyChannelView> list() {
        return channelRepository.findAll().stream().map(this::toView).toList();
    }

    /** 转发是否已配置好。管理端据此在配置**之前**给提示，而不是等人填完表单再报错。 */
    public boolean isReady() {
        return crypto.isReady();
    }

    public NotifyChannelView detail(Long id) {
        return toView(require(id));
    }

    @Transactional
    public NotifyChannelView create(NotifyChannelRequest request) {
        NotifyChannel channel = new NotifyChannel();
        applyFields(channel, request, true);
        channelRepository.save(channel);

        log.info("新建转发渠道：{}（{}）", channel.getName(), channel.getType());
        notifyChanged();
        return toView(channel);
    }

    @Transactional
    public NotifyChannelView update(Long id, NotifyChannelRequest request) {
        NotifyChannel channel = require(id);
        applyFields(channel, request, false);
        channelRepository.save(channel);
        notifyChanged();
        return toView(channel);
    }

    /**
     * 删除渠道。连带处理三件事：
     *
     * <ul>
     *   <li><b>规则里的引用</b>（{@code notify_route_channel}）—— 删掉。留着的话
     *       那条规则看起来还配着这个渠道，实际永远不会投递。</li>
     *   <li><b>因此变成「无目标」的规则</b> —— 自动停用。一条匹配得上短信、
     *       却没有任何投递目标的规则是纯粹的误导：它在列表上看起来在工作。
     *       停用是把它从「假装在工作」变成「明确关着」，后者才促使人去修。</li>
     *   <li><b>未投递的记录</b> —— 置为「已取消」。它们再也发不出去了，
     *       留着只会一直显示成「待投递 + 下次重试」。</li>
     * </ul>
     *
     * <p>**已成功的记录保留**：那是「这条短信后来怎么样了」的唯一凭据，
     * 删掉之后没人说得清当初到底转发过没有。
     *
     * <p>顺序不能调：先删引用、再找没目标的规则。反过来的话找到的是删之前的状态，
     * 一条都找不到。
     */
    @Transactional
    public void delete(Long id) {
        NotifyChannel channel = require(id);

        int affectedRoutes = routeRepository.deleteChannelReferences(id);
        int disabledRoutes = disableRoutesLeftWithoutChannels();
        int cancelled = deliveryRepository.cancelUnsentForChannel(id, "渠道已删除，本条不再投递");
        channelRepository.delete(channel);

        log.warn("删除转发渠道：{}（id={}），影响 {} 条规则（其中 {} 条已无目标、自动停用），{} 条未投递记录已取消",
                channel.getName(), id, affectedRoutes, disabledRoutes, cancelled);

        notifyChanged();
        // 上面那句「已无目标、自动停用」改的是**规则**，而转发规则页可能正开着 ——
        // 顺手也推一条，否则那边要等用户自己刷新才知道有几条被停了。
        if (disabledRoutes > 0) {
            adminEvents.broadcast(AdminEventBroadcaster.EVENT_ROUTES, Map.of());
        }
    }

    /** @return 被停用的规则数 */
    private int disableRoutesLeftWithoutChannels() {
        List<NotifyRoute> orphans = routeRepository.findRoutesWithoutChannels();
        int disabled = 0;

        for (NotifyRoute route : orphans) {
            if (!route.isEnabled()) {
                continue; // 本来就停着的，不用动 —— 也不再记一次日志
            }
            route.setEnabled(false);
            disabled++;
            log.warn("转发规则「{}」的渠道已被删光，已自动停用", route.getRouteName());
        }

        if (disabled > 0) {
            routeRepository.saveAll(orphans);
        }
        return disabled;
    }

    @Transactional
    public NotifyChannelView setEnabled(Long id, boolean enabled) {
        NotifyChannel channel = require(id);
        channel.setEnabled(enabled);

        if (enabled) {
            // 重新启用时把连续失败清掉：否则它一上线就还差一次失败到阈值就被自动停用，
            // 看起来像「刚打开又自己关了」。
            channel.setConsecutiveFailures(0);
            channelRepository.save(channel);
            log.info("转发渠道 {} 已启用", channel.getName());
        } else {
            channelRepository.save(channel);

            // **停用时把还没发出去的记录一并取消。**
            //
            // 保留它们没有任何用：那些验证码早就过期了，重新启用时一次性补发出去
            // 只是噪声。而留着还会让管理端一直显示「待投递 + 下次重试 13:51」——
            // 一个停在过去的时间，读起来像马上要发出去。
            int cancelled = deliveryRepository.cancelUnsentForChannel(
                    id, "渠道已停用，本条不再投递");
            log.warn("转发渠道 {} 已停用，顺带取消了 {} 条未投递记录", channel.getName(), cancelled);
        }

        notifyChanged();
        return toView(channel);
    }

    /**
     * 测试发送：不建投递记录、不过限流，直接调一次发送实现。
     *
     * <p>配置期的必需能力 —— 渠道配好之后必须能立刻验证，否则要等到真有短信才
     * 知道配没配对，而那时候出问题已经晚了（用户等着验证码）。
     *
     * <p>用一条**假的**短信内容，不含任何真实验证码：测试消息不该把真实凭据带出去。
     */
    public SendResult test(Long id) {
        NotifyChannel channel = require(id);
        ChannelSender sender = senderRegistry.get(channel.getType());
        if (sender == null) {
            return SendResult.failed(0, "没有该渠道类型的发送实现：" + channel.getType());
        }

        SmsMessage sample = sampleMessage();
        RenderedMessage message = messageFactory.build(sample, "测试设备");

        try {
            return sender.send(ChannelConfig.of(decrypt(channel)), message);
        } catch (MissingConfigException | SsrfBlockedException e) {
            return SendResult.failed(0, e.getMessage());
        } catch (Exception e) {
            log.warn("测试发送异常：channelId={}", id, e);
            return SendResult.failed(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 当前**启用**的渠道名，供设备端在被问「测了会发去哪」时先回答。
     *
     * <p>只有名字，不带任何配置：设备的持有者要判断的是「点下去会打扰到谁」，
     * 而不是这些渠道怎么配的 —— 配置里有 webhook 地址与 token，那是管理端的范围。
     *
     * <p>存在的理由：一个渠道都没启用时，按钮点下去只会返回一个空列表，
     * 而人看到的是「测试通过了但什么都没发生」。把「会发给谁」提前摆出来，
     * 这种情况在点之前就说得清。
     */
    public List<String> enabledChannelNames() {
        return channelRepository.findByEnabledTrue().stream()
                .map(NotifyChannel::getName)
                .toList();
    }

    /**
     * 把所有**启用**的渠道各测一条，返回逐个结果。设备端的「一键测转发链路」用它。
     *
     * <p>为什么设备端要能自己发起：转发断掉是**静默**的 —— 手机照收、心跳照发、
     * 管理端设备列表上一切正常，只是码再也送不到微信里。设备持有者（现场那个人）
     * 没有任何别的界面能回答「码到底送出去了没有」，只能来问管理员。给一个按钮，
     * 问题在十秒内定性。
     *
     * <p>返回逐个渠道的结果而不是一个总成败：坏了哪一个才是要处置的东西。
     * 消息内容用 {@link #sampleMessage()}，**不含任何真实验证码**，且正文里写明是测试。
     */
    public List<NotifyTestResult> testAllEnabled() {
        return channelRepository.findByEnabledTrue().stream()
                .map(channel -> {
                    SendResult result = test(channel.getId());
                    return new NotifyTestResult(
                            channel.getId(),
                            channel.getName(),
                            result.success(),
                            result.success() ? null : result.describe());
                })
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private void applyFields(NotifyChannel channel, NotifyChannelRequest request, boolean isCreate) {
        if (request.getName() != null && !request.getName().isBlank()) {
            channel.setName(request.getName().trim());
        } else if (isCreate) {
            throw new IllegalArgumentException("渠道名不能为空");
        }

        if (request.getType() != null && !request.getType().isBlank()) {
            channel.setType(parseType(request.getType()));
        } else if (isCreate) {
            throw new IllegalArgumentException("渠道类型不能为空");
        }

        // 配置：新建时必须给；修改时留空表示不动（见 NotifyChannelRequest 的说明）
        if (isCreate) {
            if (request.getConfig() == null || request.getConfig().isEmpty()) {
                throw new IllegalArgumentException("新建渠道必须提供配置");
            }
            channel.setConfigCipher(encrypt(request.getConfig()));
        } else if (request.getConfig() != null && !request.getConfig().isEmpty()) {
            Map<String, Object> merged = NotifyConfigMasker.merge(decrypt(channel), request.getConfig());
            channel.setConfigCipher(encrypt(merged));
        }

        if (request.getRateLimitPerMin() != null) {
            channel.setRateLimitPerMin(Math.max(0, request.getRateLimitPerMin()));
        } else if (isCreate) {
            channel.setRateLimitPerMin(defaultRateLimit(channel.getType()));
        }

        if (request.getMaxRetries() != null) {
            channel.setMaxRetries(Math.max(0, request.getMaxRetries()));
        }

        if (request.getEnabled() != null) {
            channel.setEnabled(request.getEnabled());
        }
    }

    /** 取该渠道类型的默认限额（各 Sender 按官方硬限的保守档给）。 */
    private int defaultRateLimit(NotifyChannelType type) {
        ChannelSender sender = senderRegistry.get(type);
        return sender == null ? 20 : sender.defaultRateLimitPerMin();
    }

    private NotifyChannelType parseType(String raw) {
        try {
            return NotifyChannelType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的渠道类型：" + raw);
        }
    }

    private String encrypt(Map<String, Object> config) {
        try {
            return crypto.encrypt(objectMapper.writeValueAsString(config));
        } catch (NotifyNotConfiguredException e) {
            // 原样透传：它带的是「去设哪两个环境变量」那套操作指引，
            // 包一层「渠道配置加密失败」只会把指路的那句话推到 Caused by 里，
            // 而使用者看到的是外层那句没用的概括（这个坑踩过一次）
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("渠道配置加密失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> decrypt(NotifyChannel channel) {
        try {
            return objectMapper.readValue(
                    crypto.decrypt(channel.getConfigCipher()),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (NotifyNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "渠道配置解密失败（密钥可能换过）：" + channel.getName() + "，需要重新配置该渠道", e);
        }
    }

    private NotifyChannel require(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("转发渠道不存在: " + id));
    }

    private NotifyChannelView toView(NotifyChannel channel) {
        NotifyChannelView view = new NotifyChannelView();
        view.setId(channel.getId());
        view.setName(channel.getName());
        view.setType(channel.getType().name());
        view.setConfig(NotifyConfigMasker.mask(decrypt(channel)));
        view.setRateLimitPerMin(channel.getRateLimitPerMin());
        view.setMaxRetries(channel.getMaxRetries());
        view.setEnabled(channel.isEnabled());
        view.setLastSuccessAt(channel.getLastSuccessAt());
        view.setLastErrorAt(channel.getLastErrorAt());
        view.setLastError(channel.getLastError());
        view.setConsecutiveFailures(channel.getConsecutiveFailures());
        view.setBacklog(deliveryRepository.countBacklog(channel.getId()));
        view.setCreatedAt(channel.getCreatedAt());
        view.setUpdatedAt(channel.getUpdatedAt());
        return view;
    }

    /** 测试消息的样例内容。**不含任何真实验证码**，只是形状上像一条验证码短信。 */
    private SmsMessage sampleMessage() {
        SmsMessage sms = new SmsMessage();
        sms.setId(0L);
        sms.setSender("106900000000");
        sms.setPhone("13800000000");
        sms.setContent("【测试】这是一条来自短信网关的测试消息，验证码是 123456，请勿当真。");
        sms.setCode("123456");
        sms.setReceiveTime(LocalDateTime.now());
        return sms;
    }

    /** 给控制器用：测试结果的展示文本（截断，避免把对端的长响应直接甩给前端）。 */
    public String previewOf(SendResult result) {
        String detail = result.body() == null ? result.describe() : result.describe();
        return NotifyRedactor.redact(NotifyRedactor.truncate(detail, PREVIEW_LENGTH));
    }
}
