package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理后台密钥视图。字段名对齐前端 types/index.ts 的 ApiKey。
 *
 * <p>{@code apiKey} 回传的是**完整明文**：列表要支持「点显示看完整值 / 点复制」，
 * 打码只做在展示层，所以别把它当成脱敏字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyView {

    private Long id;
    private String name;

    /** 完整密钥明文。 */
    private String apiKey;

    private boolean enabled;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
