package com.smsgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 管理后台相关 Bean。
 * 只引入 spring-security-crypto 做密码哈希，不启用 Spring Security 的自动配置，
 * 以免改变现有设备端接口的鉴权行为（现有鉴权由自定义拦截器负责）。
 */
@Configuration
public class AdminConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
