package com.smsgateway.interceptor;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 外部调用方鉴权。密钥由管理后台「API 密钥」页签发，存 api_key 表，
 * 校验逻辑见 {@link ApiKeyService#validate(String)}。
 *
 * <p>写法与 {@link AdminAuthInterceptor} 保持一致：拦截器直接注入 Service，
 * 依赖方向是 SecurityConfig → Interceptor → Service → Repository，无环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientAuthInterceptor implements HandlerInterceptor {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 浏览器预检请求不带 Authorization 头（规范如此，浏览器不会给它加自定义头），
        // 拦下来会让所有浏览器端调用方卡在 401，真实请求根本发不出去。放过去交给 CORS 处理。
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // Extract Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
            return false;
        }

        String token = authHeader.substring(7).trim();

        String keyName = apiKeyService.validate(token);
        if (keyName == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
            return false;
        }

        // 供后续日志/排查识别是哪个调用方
        request.setAttribute("apiKeyName", keyName);
        return true;
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> errorResult = ApiResult.error(status, message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }
}
