package com.smsgateway.model.enums;

/**
 * 转发渠道类型。
 *
 * <p>只列**已经实现**的，没实现的不放进来：枚举多一个值，管理端的类型选择器就多一个
 * 能选、能存库、但发不出去的选项 —— 那种失败要到真正有短信时才暴露，而配置的人
 * 那一刻早就离开了。{@code ChannelSenderRegistry} 启动时会校验这条，少一个实现就拒绝启动。
 *
 * <p>加一种渠道 = 在这里加一个值 + 写一个 {@code ChannelSender} 实现。
 * 表结构、调度器、管理端下拉都不用改（下拉是从 {@code /channel/types} 取的）。
 */
public enum NotifyChannelType {

    // ---------------------------------------------------------------- 一 URL 直发（无状态）

    /** 企业微信群机器人：一个 URL 即全部凭证，无签名、无 token 管理。 */
    WECOM_BOT,

    /** 飞书自定义机器人：可选加签（签名算法与钉钉**完全不同**，见 BotSignatures）。 */
    FEISHU_BOT,

    /** 钉钉自定义机器人：创建时必须至少配一种安全设置，否则直接拒。 */
    DINGTALK_BOT,

    /** Telegram Bot：用 HTML 模式，**不要用 MarkdownV2**（18 个字符要转义，短信正文里全是）。 */
    TELEGRAM_BOT,

    /** Slack Incoming Webhook：限流最严（1 条/秒），超限可能永久禁用应用。 */
    SLACK_WEBHOOK,

    /** WxPusher：官方文档点名「短信转发系统」是其典型场景，免费且无每日条数上限。 */
    WXPUSHER,

    /** Server酱 Turbo：免费额度只有 5 条/天，够测试不够用。 */
    SERVERCHAN,

    /** PushPlus：接口是**异步**的，返回 200 只代表收到请求，不代表发送成功。 */
    PUSHPLUS,

    /** 通用 webhook：自定义 URL + 可选 HMAC-SHA256 签名（Standard Webhooks）。 */
    GENERIC_WEBHOOK,

    // ---------------------------------------------------------------- 需要 access_token 生命周期管理

    /** 企业微信应用消息：需要企业主体 + 自建应用，token 7200 秒过期且会被提前作废。 */
    WECOM_APP
}
