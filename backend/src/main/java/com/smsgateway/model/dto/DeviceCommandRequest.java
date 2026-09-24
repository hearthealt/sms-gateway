package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端签发一条远程指令。
 *
 * <p>{@code type} 是 String 而不是枚举，**刻意的**：见
 * {@link DeviceCommandAckItem} 的说明 —— 枚举会让一个不认识的取值变成
 * {@code HttpMessageNotReadableException}，而全局异常处理器没有接它，结果是 500。
 * 管理端点错一个类型名，应该得到「不认识的指令类型: XXX」，不是「服务器内部错误」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandRequest {

    /** 见 DeviceCommandType 枚举。 */
    @NotBlank(message = "指令类型不能为空")
    @Size(max = 32, message = "指令类型超长")
    private String type;

    /** 只有「修改本机号码」需要它。 */
    @Size(max = 255, message = "参数超长")
    private String argument;
}
