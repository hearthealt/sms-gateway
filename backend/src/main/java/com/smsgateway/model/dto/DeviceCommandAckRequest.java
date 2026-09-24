package com.smsgateway.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 设备回执一批指令的执行结果。
 *
 * <p>上限 10 条：心跳一次最多下发 5 条，回执不该比下发还多。少了这个上限，
 * 一个被篡改的设备可以往这张表里灌任意多条回执。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandAckRequest {

    @NotEmpty(message = "results 不能为空")
    @Size(max = 10, message = "一次最多回执 10 条")
    @Valid
    private List<DeviceCommandAckItem> results;
}
