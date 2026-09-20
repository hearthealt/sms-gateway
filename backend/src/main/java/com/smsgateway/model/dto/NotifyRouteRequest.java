package com.smsgateway.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建 / 修改转发规则。
 *
 * <p>匹配条件的写法与采集规则完全一致：{@code %} 匹配任意长度、{@code _} 匹配单个字符、
 * {@code REGEX} 用包含匹配（不是整体匹配）。空字段表示**不限制这一项**。
 *
 * <p>与采集规则最关键的差别是**并集语义**：一条规则可以配多个渠道，
 * 多条规则命中时渠道是累加的，不像采集规则那样首条命中即定论。
 */
@Data
public class NotifyRouteRequest {

    @Size(max = 100, message = "routeName 超长（上限 100）")
    private String routeName;

    @Size(max = 255, message = "senderPattern 超长（上限 255）")
    private String senderPattern;

    @Size(max = 255, message = "keywordPattern 超长（上限 255）")
    private String keywordPattern;

    /** {@code EXACT} / {@code LIKE} / {@code REGEX}。留空则新建时用 LIKE。 */
    private String matchType;

    @Size(max = 128, message = "deviceId 超长（上限 128）")
    private String deviceId;

    @Size(max = 64, message = "phonePattern 超长（上限 64）")
    private String phonePattern;

    /** 这条规则命中后发到哪些渠道。空列表等于规则不起作用，Service 会拒绝。 */
    private List<Long> channelIds;

    private Boolean enabled;
}
