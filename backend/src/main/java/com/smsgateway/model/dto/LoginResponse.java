package com.smsgateway.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String username;
    private String displayName;

    /**
     * 令牌有效期（秒）。
     *
     * <p>带出来是为了让前端能自己判断过期 —— 它此前只把令牌塞进 localStorage、
     * 只判「有没有」，服务端 TTL 一到，用户看到的是「界面一切正常、点什么都失败」。
     * 有这个值之后，路由守卫和请求拦截器都能在过期时直接引导重新登录。
     */
    private long expiresIn;
}
