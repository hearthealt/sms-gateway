package com.smsgateway.controller;

import com.smsgateway.service.AdminEventBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 管理后台的事件流。
 *
 * <p>路径挂在 /api/admin/** 下，因此照常被 AdminAuthInterceptor 保护 ——
 * 前端用 fetch 带上 Bearer 头来订阅（EventSource 带不了头，见 AdminEventBroadcaster 的说明）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final AdminEventBroadcaster broadcaster;

    /**
     * 订阅事件流（Server-Sent Events）。
     *
     * <p>返回的是流不是 ApiResult —— 这个连接会一直挂着，不能再当成一次性 JSON 响应。
     * 项目里没有 ResponseBodyAdvice，不会被统一包装逻辑碰到；如果有，这里要单独放行。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return broadcaster.subscribe();
    }
}
