package com.smsgateway.interceptor;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.model.enums.EventType;
import com.smsgateway.service.DeviceService;
import com.smsgateway.service.EventLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceAuthInterceptor implements HandlerInterceptor {

    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;
    private final EventLogService eventLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // Skip auth for register endpoint.
        // 用 equals 而不是 endsWith：后缀匹配在将来多出别的 /register 路由时会误放行。
        String requestURI = request.getRequestURI();
        if (requestURI.equals("/api/device/register")) {
            return true;
        }

        // Extract Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // **不记事件**。这一支任何一个不认识的服务都能随便触发，记它等于给了一个
            // 无需认证就能往事件表里写行的入口；而它对排查也没有价值 ——
            // 本应用发出的每个请求都会带这个头，缺头只会是扫描器或手写的调试请求。
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
            return false;
        }

        String token = authHeader.substring(7).trim();

        // Validate token
        SmsDevice device = deviceService.getDeviceByToken(token);
        if (device == null) {
            // **这一支也不记事件**，与上面缺头那一支同一个理由：请求方是谁完全不可知，
            // 任何人 `curl -H 'Authorization: Bearer x'` 就能让服务端写一行并推一次 SSE。
            // 记了就等于开了一个无需认证、一请求一行的写库入口 ——
            // 而这个接口在公网上，事件表又保留 7 天，足以被刷爆。
            //
            // 代价是「某台设备的令牌是什么时候开始被拒的」在服务端查不到。可以接受：
            // 设备端自己会记 DEVICE_TOKEN_REJECTED（那是这次改动真正的落点），
            // 而管理端看心跳中断/设备离线也能看出同一件事。
            //
            // **令牌本身在任何情况下都不写进任何地方。**
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid device token");
            return false;
        }

        // 被管理员禁用的设备：只拦短信上报，**放行心跳**。
        //
        // 放行心跳是刻意的。心跳响应里会带回设备状态，那是设备唯一能发现自己「已被恢复」的
        // 通道；同时管理端的 last_heartbeat_at 会继续更新，管理员因此能区分
        // 「禁用但设备还活着」和「禁用且失联」。若连心跳一起拦掉，管理端看到的时间戳会冻结，
        // 反而无法判断禁用是否真的送达了手机。
        //
        // 这里直接写响应而不抛异常：异常会被 GlobalExceptionHandler 兜成 500，
        // 看起来像服务器故障，而不是一次明确的策略拒绝。
        if ("DISABLED".equals(device.getStatus()) && requestURI.equals("/api/sms/receive")) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "Device disabled by administrator");
            recordQuietly(EventType.SMS_REJECTED_DEVICE_DISABLED, device, "设备已被管理员禁用，上报被拒");
            return false;
        }

        // Set device info in request attribute for downstream use
        request.setAttribute("deviceId", device.getDeviceId());
        request.setAttribute("devicePk", device.getId());

        return true;
    }

    /**
     * 记一条事件，失败不影响这次拒绝。
     *
     * <p>拦截器里记事件是**旁路**：认证结论已经定了、响应已经写出去了，
     * 这时写库失败再把异常抛出去，只会把一个明确的 401/403 变成 500，
     * 让设备端把它当成「服务器故障」去重试 —— 比不记这条事件坏得多。
     */
    private void recordQuietly(EventType type, SmsDevice device, String reason) {
        try {
            eventLogService.record(type, device, reason);
        } catch (Exception e) {
            log.warn("Failed to record device auth event {}", type, e);
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> errorResult = ApiResult.error(status, message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }
}