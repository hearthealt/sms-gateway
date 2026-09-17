package com.smsgateway.interceptor;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.entity.SmsDevice;
import com.smsgateway.service.DeviceService;
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
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
            return false;
        }

        String token = authHeader.substring(7).trim();

        // Validate token
        SmsDevice device = deviceService.getDeviceByToken(token);
        if (device == null) {
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
            return false;
        }

        // Set device info in request attribute for downstream use
        request.setAttribute("deviceId", device.getDeviceId());
        request.setAttribute("devicePk", device.getId());

        return true;
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> errorResult = ApiResult.error(status, message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }
}