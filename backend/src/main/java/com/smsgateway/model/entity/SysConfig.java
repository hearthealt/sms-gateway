package com.smsgateway.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条运行期配置。
 *
 * <p>值一律按**字符串**存。理由：这些配置的类型只有三种（布尔、整数、字符串），
 * 而按字符串存的话，加一项新配置**不需要改表结构** —— 加一个枚举值就行。
 * 类型转换在 {@code SysConfigService} 里按枚举上的 {@code Type} 做，
 * 转换失败时退回默认值并记日志，不会因为某一行写坏了就让整个设置页打不开。
 *
 * <p>取值只从 {@link com.smsgateway.model.enums.SysConfigKey} 走，
 * 所以表里出现不认识的键时会被忽略（不报错）：那种行多半是旧版本留下的，
 * 升级后不该因为它的存在起不来。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_config")
public class SysConfig {

    /** 配置键，见 {@code SysConfigKey.key()}。自然主键，不另设自增 id。 */
    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onWrite() {
        updatedAt = LocalDateTime.now();
    }
}
