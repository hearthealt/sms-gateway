package com.smsgateway.controller;

import com.smsgateway.model.dto.*;
import com.smsgateway.service.AdminSmsService;
import com.smsgateway.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AdminSmsService adminSmsService;

    @PostMapping("/register")
    public ResponseEntity<ApiResult<DeviceRegisterResponse>> register(@Valid @RequestBody DeviceRegisterRequest request) {
        log.info("Device registration request: deviceId={}", request.getDeviceId());
        DeviceRegisterResponse response = deviceService.register(request);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /**
     * 心跳。响应体里带回设备状态，设备据此得知自己是否被管理员禁用。
     *
     * 心跳对被禁用的设备**放行**（见 DeviceAuthInterceptor），这是刻意的：
     * 它是设备唯一能发现自己被恢复的通道，也让管理端能持续看到设备是否还活着。
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<ApiResult<HeartbeatResponse>> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        log.debug("Heartbeat request: deviceId={}", request.getDeviceId());
        String status = deviceService.heartbeat(request);
        return ResponseEntity.ok(ApiResult.success(new HeartbeatResponse(status)));
    }

    /**
     * 本设备在服务端的短信记录。
     *
     * 挂在 /api/device/** 下，因此自动受 DeviceAuthInterceptor 保护。设备身份取自
     * 拦截器写入的请求属性，而**不是**请求参数 —— 否则任何设备都能查看到别人的记录。
     */
    @GetMapping("/sms")
    public ResponseEntity<ApiResult<PageResult<SmsView>>> mySms(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "true") boolean includeIgnored,
            HttpServletRequest httpRequest) {

        String deviceId = (String) httpRequest.getAttribute("deviceId");
        return ResponseEntity.ok(ApiResult.success(
                adminSmsService.byDevice(deviceId, Math.max(page, 1), pageSize, includeIgnored)));
    }

    /**
     * 本设备今日的短信统计。
     *
     * 设备端的「今日短信 / 今日验证码」读这里而不是本地库：本地库会因为清理历史、
     * 清除应用数据而与服务端不一致，出现过「数字是 0、点进去有内容」。
     */
    @GetMapping("/sms/stats")
    public ResponseEntity<ApiResult<DeviceSmsStats>> smsStats(HttpServletRequest httpRequest) {
        String deviceId = (String) httpRequest.getAttribute("deviceId");
        return ResponseEntity.ok(ApiResult.success(deviceService.todayStats(deviceId)));
    }
}
