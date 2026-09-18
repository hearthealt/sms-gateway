package com.smsgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许的跨源来源，逗号分隔。默认 "*" 是为了本机开发（vite 跑在 5173）不用配就能用。
     *
     * <p>当前这套配置本身不容易被利用：鉴权走 {@code Authorization} 头而不是 Cookie，
     * 浏览器不会自动带上，别的站点即使发得出请求也拿不到数据。
     *
     * <p>风险在**将来** —— 一旦引入 Cookie 会话、或打开 {@code allowCredentials}，
     * "*" 会立刻变成可利用配置。所以把入口留出来（{@code APP_CORS_ALLOWED_ORIGINS}），
     * 生产收紧时不用改代码。注意：收紧成具体域名后，管理后台若不是同源部署，
     * 预检请求会直接失败（AdminAuthInterceptor 必须放行 OPTIONS，那一处已单独处理）。
     */
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Ordered interceptors - device auth runs first, client auth runs on specific paths
        // Registered in SecurityConfig via @Bean to ensure proper ordering
    }
}