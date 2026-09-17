package com.smsgateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 签发密钥入参。密钥值由服务端生成，不接受客户端传入，
 * 避免调用方自己指定一个可预测的值。过期时间留空表示不过期。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyRequest {

    @NotBlank(message = "name cannot be empty")
    private String name;

    private LocalDateTime expiresAt;
}
