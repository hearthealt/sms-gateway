package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.DailyCount;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.SmsView;
import com.smsgateway.service.AdminSmsService;
import com.smsgateway.util.PageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin/sms")
@RequiredArgsConstructor
public class AdminSmsController {

    private final AdminSmsService adminSmsService;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<PageResult<SmsView>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "15") int pageSize,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "false") boolean includeIgnored,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endDate) {

        return ResponseEntity.ok(ApiResult.success(adminSmsService.list(
                PageUtil.safePage(page), PageUtil.safePageSize(pageSize), phone, code, startDate, endDate, deviceId, includeIgnored)));
    }

    @GetMapping("/device/{deviceId}")
    public ResponseEntity<ApiResult<PageResult<SmsView>>> byDevice(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "false") boolean includeIgnored) {

        return ResponseEntity.ok(ApiResult.success(
                adminSmsService.byDevice(deviceId, PageUtil.safePage(page), PageUtil.safePageSize(pageSize), includeIgnored)));
    }

    @GetMapping("/stats/daily")
    public ResponseEntity<ApiResult<List<DailyCount>>> daily(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(ApiResult.success(
                adminSmsService.daily(Math.max(Math.min(days, 365), 1))));
    }
}
