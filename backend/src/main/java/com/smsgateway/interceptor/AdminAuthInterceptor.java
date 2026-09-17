package com.smsgateway.interceptor;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.service.AdminAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理后台鉴权。写法与 {@link ClientAuthInterceptor} 保持一致，
 * 区别在于 token 不是与配置文件比对，而是查 Redis 中的登录会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminAuthService adminAuthService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 登录接口本身不需要鉴权
        if (request.getRequestURI().endsWith("/admin/auth/login")) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
            return false;
        }

        String token = authHeader.substring(7).trim();
        String username = adminAuthService.resolveUsername(token);
        if (username == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired admin token");
            return false;
        }

        request.setAttribute("adminUsername", username);
        request.setAttribute("adminToken", token);
        return true;
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> errorResult = ApiResult.error(status, message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }
}
