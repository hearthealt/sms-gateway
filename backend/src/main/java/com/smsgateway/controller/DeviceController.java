package com.smsgateway.controller;

import com.smsgateway.model.dto.*;
import com.smsgateway.service.AdminSmsService;
import com.smsgateway.service.DeviceService;
import com.smsgateway.util.PageUtil;
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
    public ResponseEntity<ApiResult<HeartbeatResponse>> heartbeat(
            @Valid @RequestBody HeartbeatRequest request,
            HttpServletRequest httpRequest) {

        // 同 /api/sms/receive：身份取认证结果，不用请求体里的 deviceId。
        // 否则可拿自己的令牌覆写他人设备的 deviceName / phoneNumber / battery。
        String deviceId = (String) httpRequest.getAttribute("deviceId");

        log.debug("Heartbeat request: deviceId={}", deviceId);
        String status = deviceService.heartbeat(deviceId, request);
        return ResponseEntity.ok(ApiResult.success(new HeartbeatResponse(status)));
    }

    /**
     * 设备主动报告「网关已停止」。
     *
     * <p>心跳是「我还活着」的单向信号 —— 用户把网关停了之后心跳就断了，服务端要等 90 秒
     * 超时才判离线，那 90 秒管理后台一直显示在线。设备在停止时补这一条，后台立刻变灰。
     *
     * <p>尽力而为：进程被系统杀掉时发不出这个请求，那种情况仍由心跳超时兜底。
     * 身份同样取自拦截器的认证结果，不接受请求体里自报的设备号。
     */
    @PostMapping("/offline")
    public ResponseEntity<ApiResult<Void>> offline(HttpServletRequest httpRequest) {
        String deviceId = (String) httpRequest.getAttribute("deviceId");
        deviceService.markOffline(deviceId);
        return ResponseEntity.ok(ApiResult.success(null));
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
                adminSmsService.byDevice(deviceId, PageUtil.safePage(page), PageUtil.safePageSize(pageSize), includeIgnored)));
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
