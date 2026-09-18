package com.smsgateway.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备恢复码：管理员在控制台为某台设备签发，设备扫码后取回身份。
 *
 * <p>{@code enrollSecret} 是**明文**，只在签发的那一次响应里出现 —— 服务端只保存它的
 * SHA-256，之后就再也拿不回来了。管理员没记下不要紧，重新签一张即可（旧的自然作废）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryCodeView {

    private String deviceId;

    /** 明文重注册密钥，仅此一次返回。 */
    private String enrollSecret;
}
