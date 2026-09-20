package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.EnrollTokenView;
import com.smsgateway.service.DeviceEnrollTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 设备接入口令的管理端接口，挂在 {@code /api/admin/**} 下 —— 因此自动受
 * {@code AdminAuthInterceptor} 保护，不需要额外的鉴权配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/enroll-token")
@RequiredArgsConstructor
public class AdminEnrollTokenController {

    private final DeviceEnrollTokenService enrollTokenService;

    /**
     * 当前口令状态。未生成时 token 为 null。
     *
     * <p>返回**明文**：控制台要把它显示进「快速连接」的二维码里。与 API 密钥列表
     * 一样，前端默认打码，点「显示」才展开。
     */
    @GetMapping
    public ResponseEntity<ApiResult<EnrollTokenView>> current() {
        return ResponseEntity.ok(ApiResult.success(enrollTokenService.view()));
    }

    /**
     * 生成一张新口令并启用准入校验；已有口令时即轮换。
     *
     * <p>用 POST 而不是 GET：这是一次会作废旧口令的写操作，轮换后现场那张旧二维码
     * 立刻失效。
     */
    @PostMapping("/rotate")
    public ResponseEntity<ApiResult<EnrollTokenView>> rotate() {
        log.warn("Admin rotated the device enrollment token");
        return ResponseEntity.ok(ApiResult.success(enrollTokenService.rotate()));
    }

    /**
     * 启用 / 停用准入校验。停用不删除口令，重新启用不必换一张。
     *
     * <p>关掉之后注册接口退回完全开放（任何知道地址的人都能注册设备）——
     * 这是给「临时批量接入」留的口子，控制台那边必须把这句话说清楚。
     */
    @PutMapping("/enabled")
    public ResponseEntity<ApiResult<EnrollTokenView>> setEnabled(@RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(enrollTokenService.setEnabled(enabled)));
    }
}
