package com.smsgateway.model.dto;

import lombok.Data;

/** 改一项运行期配置。 */
@Data
public class SysConfigRequest {

    /** 配置键，见 {@code SysConfigKey.key()}。 */
    private String key;

    /**
     * 新值。一律是字符串，类型由后端按该键的类型校验 ——
     * 前端传 {@code true} 还是 {@code "true"} 都能过（Jackson 会转成字符串）。
     */
    private String value;
}
