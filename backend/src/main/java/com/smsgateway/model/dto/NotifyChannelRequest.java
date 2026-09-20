package com.smsgateway.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 新建 / 修改转发渠道。
 *
 * <p><b>{@code config} 的约定：留空（null 或空 Map）= 保持原值不变</b>，
 * 不是清空。因为管理端回显的是打码值，管理员若不动配置就把打码值原样提交回来，
 * 那串 {@code ****} 会被当成新地址存进去 —— 渠道从此静默失效，
 * 而现场完全看不出什么时候坏的。
 *
 * <p>缺字段时不在这一层用 {@code @NotBlank} 挡成 400，而是交给 Service 抛带说明的错 ——
 * 与 {@code DeviceRegisterRequest} 的处理一致：现场需要的是「该填哪个字段」，
 * 不是「cannot be empty」。
 */
@Data
public class NotifyChannelRequest {

    @Size(max = 100, message = "name 超长（上限 100）")
    private String name;

    /** 渠道类型，取值见 {@code NotifyChannelType}。 */
    private String type;

    /** 明文配置。留空 = 不修改（见类注释）。 */
    private Map<String, Object> config;

    /** 0 = 不限。留空则用该渠道类型的默认值。 */
    private Integer rateLimitPerMin;

    private Integer maxRetries;

    private Boolean enabled;
}
