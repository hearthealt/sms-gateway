package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 规则新增/编辑入参。仅暴露可编辑字段，
 * 避免直接绑定实体导致 id / createdAt / updatedAt 被客户端覆盖。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectRuleRequest {

    @NotBlank(message = "ruleName cannot be empty")
    private String ruleName;

    @NotBlank(message = "senderPattern cannot be empty")
    private String senderPattern;

    private String keywordPattern = "";

    private String matchType = "EXACT";

    private String action = "collect";

    private int priority = 0;

    private boolean enabled = true;

    private String description;
}
