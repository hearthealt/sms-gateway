package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.dto.SmsOutboundView;
import com.smsgateway.model.dto.SmsSendRequest;
import com.smsgateway.service.SmsOutboundService;
import com.smsgateway.util.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端：外发短信。
 *
 * <p><b>这是全项目唯一一个会主动产生费用的写接口。</b>界面上的提交按钮必须带二次确认，
 * 并把「按运营商计费、发出去无法撤回」写在确认框里 —— 服务端这边只管住每日上限。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/outbound")
@RequiredArgsConstructor
public class AdminOutboundController {

    private final SmsOutboundService outboundService;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<PageResult<SmsOutboundView>>> list(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(ApiResult.success(outboundService.list(
                deviceId, status, PageUtil.safePage(page), PageUtil.safePageSize(pageSize))));
    }

    @PostMapping
    public ResponseEntity<ApiResult<SmsOutboundView>> send(
            @Valid @RequestBody SmsSendRequest request,
            HttpServletRequest httpRequest) {

        // 管理端必须指定设备：控制台就是「选一台设备，用它的号发一条」，
        // 而按号码反查那条路是给外部调用方用的（它可能只知道号码）。
        if (request.getDeviceId() == null || request.getDeviceId().isBlank()) {
            throw new IllegalArgumentException("请先选择用哪台设备发送");
        }

        String admin = (String) httpRequest.getAttribute("adminUsername");
        log.info("Admin {} sends SMS via device {} to {}", admin, request.getDeviceId(), request.getPhone());
        return ResponseEntity.ok(ApiResult.success(
                outboundService.enqueue(request.getDeviceId(), request, admin)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResult<SmsOutboundView>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResult.success(outboundService.cancel(id)));
    }

    /**
     * 重发一条**失败的**。
     *
     * <p>只允许 FAILED（设备明确说没发出去，重发不会重复计费）；「结果未知」的那类
     * 会被拒绝并说明原因 —— 详见 {@code SmsOutboundService.retry}。
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResult<SmsOutboundView>> retry(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResult.success(outboundService.retry(id)));
    }
}
