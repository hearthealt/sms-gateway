package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一项运行期配置，给管理端渲染用。
 *
 * <p>{@code label} / {@code description} / {@code type} 都由后端从 {@code SysConfigKey}
 * 带出来，不让前端再写一份 —— 否则同一份文案在两处各写一遍，改了一处另一处就不一致，
 * 而那种不一致只会在有人看设置页时才发现。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysConfigView {

    private String key;
    private String value;
    /** 该键在代码里的默认值。界面显示「默认」时用，也便于现场判断某项有没有被改过。 */
    private String defaultValue;
    /**
     * {@code BOOLEAN} / {@code INT} / {@code STRING} / {@code TIME}，决定前端用什么控件。
     */
    private String type;
    private String label;
    /** 第一行是简述，其余是详细说明（按 {@code \n} 分段渲染）。 */
    private String description;
    /** 分组标签，前端按它把配置分成几张卡片。 */
    private String group;
}
