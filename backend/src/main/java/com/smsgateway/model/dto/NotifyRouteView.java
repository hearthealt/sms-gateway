package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 转发规则的管理端视图。
 *
 * <p>带 {@code targetChannelNames} 是为了让列表能直接显示「这条规则发到哪几个群」——
 * 只给一串 id 的话，管理员要挨个去渠道页对，而列表存在的意义就是一眼看清。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyRouteView {

    private Long id;
    private String routeName;
    private String senderPattern;
    private String keywordPattern;
    private String matchType;
    private String deviceId;
    private String phonePattern;
    private boolean enabled;

    private List<Long> channelIds;
    /** 与 {@code channelIds} 一一对应的渠道名，便于列表直接展示。 */
    private List<String> targetChannelNames;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
