package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.DeviceView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.RecoveryCodeView;
import com.smsgateway.model.dto.StatsView;
import com.smsgateway.service.AdminDeviceService;
import com.smsgateway.util.PageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/device")
@RequiredArgsConstructor
public class AdminDeviceController {

    private final AdminDeviceService adminDeviceService;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<PageResult<DeviceView>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String phone) {
        return ResponseEntity.ok(ApiResult.success(
                adminDeviceService.list(PageUtil.safePage(page), PageUtil.safePageSize(pageSize), deviceId, phone)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResult<StatsView>> stats() {
        return ResponseEntity.ok(ApiResult.success(adminDeviceService.stats()));
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<ApiResult<DeviceView>> detail(@PathVariable String deviceId) {
        return ResponseEntity.ok(ApiResult.success(adminDeviceService.detail(deviceId)));
    }

    @PutMapping("/{deviceId}/enabled")
    public ResponseEntity<ApiResult<DeviceView>> setEnabled(@PathVariable String deviceId,
                                                           @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(adminDeviceService.setEnabled(deviceId, enabled)));
    }

    /**
     * 为设备签发一张恢复码。
     *
     * <p>两个场景会用到：设备重装后本地密钥随应用数据一起没了；以及本次变更之前注册的
     * 老设备，它们本来就没有密钥，不签一张就永远无法重新注册。
     *
     * <p>响应里带**明文**密钥，且只此一次。控制台拿它渲染二维码给设备扫。
     * 这是一条会改变设备身份的写操作，所以用 POST 而不是 GET。
     */
    @PostMapping("/{deviceId}/recovery-code")
    public ResponseEntity<ApiResult<RecoveryCodeView>> issueRecoveryCode(@PathVariable String deviceId) {
        log.warn("Admin issued a recovery code for device {}", deviceId);
        return ResponseEntity.ok(ApiResult.success(adminDeviceService.issueRecoveryCode(deviceId)));
    }

    /**
     * 删除设备。
     *
     * <p>连同该设备的短信记录一起删（理由见 AdminDeviceService.delete）。返回被一并删掉的
     * 短信条数，控制台据此在提示里说清楚「删了什么」，而不是只回一个「成功」。
     *
     * <p>设备那边不需要做任何事：它手里的令牌从此查不到设备，下一次心跳就会 401，
     * App 会提示「令牌失效，请重新注册」—— 这正是期望的行为。
     */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<ApiResult<Integer>> delete(@PathVariable String deviceId) {
        return ResponseEntity.ok(ApiResult.success(adminDeviceService.delete(deviceId)));
    }
}
