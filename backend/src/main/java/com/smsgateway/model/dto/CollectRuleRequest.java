package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 规则新增/编辑入参。仅暴露可编辑字段，
 * 避免直接绑定实体导致 id / createdAt / updatedAt 被客户端覆盖。
 *
 * <p>长度上限对齐建表脚本列宽。原先前端、后端、数据库三层里只有数据库会拒绝，
 * 而拒绝方式是抛异常 → 兜底成 500「服务器内部错误」，管理员于是往服务端故障方向排查，
 * 实际只是规则名太长。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectRuleRequest {

    @NotBlank(message = "ruleName cannot be empty")
    @Size(max = 100, message = "规则名称超长（上限 100）")
    private String ruleName;

    @NotBlank(message = "senderPattern cannot be empty")
    @Size(max = 255, message = "发送方匹配超长（上限 255）")
    private String senderPattern;

    @Size(max = 255, message = "关键词匹配超长（上限 255）")
    private String keywordPattern = "";

    @Size(max = 20, message = "匹配方式超长（上限 20）")
    private String matchType = "EXACT";

    @Size(max = 20, message = "动作超长（上限 20）")
    private String action = "collect";

    private int priority = 0;

    private boolean enabled = true;

    @Size(max = 500, message = "描述超长（上限 500）")
    private String description;
}
