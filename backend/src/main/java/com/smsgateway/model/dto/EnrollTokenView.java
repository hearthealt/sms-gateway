package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 设备接入口令的当前状态，给管理后台「快速连接」页用。
 *
 * <p>{@code token} 是**明文**：管理后台要把它显示进二维码，哈希回读不出来 ——
 * 取舍与 {@code ApiKeyView.apiKey} 一致。前端默认打码，点「显示」才展开。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrollTokenView {

    /** 明文口令。**null = 尚未生成**，此时准入校验未启用。 */
    private String token;

    /**
     * 是否启用准入校验。
     *
     * <p>注意它与 {@code token} 是两个维度：停用（false）时 token 仍然留着，
     * 重新启用不必换一张口令。前端据此区分「没生成过」和「生成过但关掉了」。
     */
    private boolean enabled;

    private LocalDateTime updatedAt;
}
