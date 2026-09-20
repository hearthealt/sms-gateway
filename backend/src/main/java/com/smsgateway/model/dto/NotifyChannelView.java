package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 转发渠道的管理端视图。
 *
 * <p>注意 {@code config} 是**打码过**的：真正的 webhook 地址在库里是密文，
 * 这里回显的只是「头尾各留 4 位」的展示值。它**不能**被原样提交回来 ——
 * 提交用的是 {@link NotifyChannelRequest}，那边的约定是「留空 = 不修改」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyChannelView {

    private Long id;
    private String name;
    private String type;

    /** 打码后的配置。key 与配置项一致，值形如 {@code 693a****5aaa}。 */
    private Map<String, Object> config;

    private int rateLimitPerMin;
    private int maxRetries;
    private boolean enabled;

    // ---- 健康状态 ----
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastErrorAt;
    /** 已脱敏的错误摘要，不会出现完整 webhook 地址。 */
    private String lastError;
    private int consecutiveFailures;
    /** 还没发出去的条数。比连续失败次数更早暴露「这个渠道卡住了」。 */
    private long backlog;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
