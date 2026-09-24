package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 新建 / 修改一条告警规则。
 *
 * <p>{@code alertType} 是 String 而不是枚举，**刻意的**（同 {@code DeviceCommandRequest}）：
 * 枚举会让一个不认识的取值在反序列化阶段抛 {@code HttpMessageNotReadableException}，
 * 而全局异常处理器没有接它 —— 管理端看到的是 500「服务器内部错误」，
 * 而真相只是类型名打错了。
 *
 * <p>{@code channelIds} 可以为空吗？**不可以**，由服务端在 Service 里挡。
 * 一条不指向任何渠道的规则永远不会发出任何东西，而它在列表上看起来和正常规则一样 ——
 * 那是最典型的「配了但没生效」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称超长")
    private String ruleName;

    /** 见 AlertType 枚举。空 = 不限类型。 */
    @Size(max = 32, message = "告警类型超长")
    private String alertType;

    /** 限定设备（业务标识），空 = 不限。 */
    @Size(max = 128, message = "设备标识超长")
    private String deviceId;

    private boolean enabled = true;

    private List<Long> channelIds;
}
