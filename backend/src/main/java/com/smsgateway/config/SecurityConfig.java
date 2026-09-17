package com.smsgateway.config;

import com.smsgateway.interceptor.AdminAuthInterceptor;
import com.smsgateway.interceptor.ClientAuthInterceptor;
import com.smsgateway.interceptor.DeviceAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final DeviceAuthInterceptor deviceAuthInterceptor;
    private final ClientAuthInterceptor clientAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Device authentication interceptor - protects device endpoints
        registry.addInterceptor(deviceAuthInterceptor)
                .addPathPatterns("/api/device/**", "/api/sms/receive")
                .order(1);

        // Client authentication interceptor - protects the external caller endpoints
        // (API Key). 设备端上报的 /api/sms/receive 不在这里，它走 deviceAuthInterceptor。
        registry.addInterceptor(clientAuthInterceptor)
                .addPathPatterns("/api/sms/wait", "/api/sms/list")
                .order(2);

        // Admin authentication interceptor - protects management console endpoints
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .order(3);
    }
}