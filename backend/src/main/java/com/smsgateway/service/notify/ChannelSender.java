package com.smsgateway.service.notify;

import com.smsgateway.model.enums.NotifyChannelType;

/**
 * 某一种转发渠道的发送实现。
 *
 * <p>加一种渠道 = 在这里实现一个类并声明 {@link #type()}，注册进
 * {@link ChannelSenderRegistry} 即可。表结构、调度器、管理端都不用动。
 */
public interface ChannelSender {

    NotifyChannelType type();

    /**
     * 发一条消息。
     *
     * <p>两类失败要用**不同的方式**表达，因为它们的重试语义相反：
     * <ul>
     *   <li>网络层失败、对端返回非 2xx —— 用 {@link SendResult} 带回来，
     *       由 {@link NotifyErrorClassifier} 按状态码与响应体判该不该重试</li>
     *   <li>配置错（缺字段、地址不是该渠道的官方域名、目标落在内网）——
     *       <b>抛异常</b>（{@link MissingConfigException} /
     *       {@link SsrfBlockedException}）。这些是终态：重试一百次也不会变对，
     *       而用 {@code SendResult.failed(0, ...)} 表达会被刚才那条规则误判成
     *       「没到达对端，可重试」，于是一条配置错的渠道会一直重试到把配额烧光。</li>
     * </ul>
     *
     * <p>其余异常一律会被调度器归为「可重试」的未知错误 —— 不确定就别放弃。
     *
     * @param config 已解密的渠道配置
     */
    SendResult send(ChannelConfig config, RenderedMessage message);

    /**
     * 该渠道的默认每分钟限额。
     *
     * <p>取官方硬限的**保守档**（留余量），而不是卡着上限 —— 钉钉超限罚 10 分钟、
     * Slack 超限可能永久禁用，这些惩罚落在渠道凭证上，代价远超「这条晚几秒发」。
     * 管理端可以在渠道上覆盖它。
     */
    int defaultRateLimitPerMin();
}
