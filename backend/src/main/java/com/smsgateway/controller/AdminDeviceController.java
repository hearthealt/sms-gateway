package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.DeviceView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.StatsView;
import com.smsgateway.service.AdminDeviceService;
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
                adminDeviceService.list(Math.max(page, 1), pageSize, deviceId, phone)));
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
}
