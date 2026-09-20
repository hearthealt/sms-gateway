package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.NotifyChannelRequest;
import com.smsgateway.model.dto.NotifyChannelView;
import com.smsgateway.model.dto.NotifyDeliveryView;
import com.smsgateway.model.dto.NotifyRouteRequest;
import com.smsgateway.model.dto.NotifyRouteView;
import com.smsgateway.model.dto.PageResult;
import com.smsgateway.model.enums.NotifyChannelType;
import com.smsgateway.service.notify.NotifyChannelService;
import com.smsgateway.service.notify.NotifyDeliveryService;
import com.smsgateway.service.notify.NotifyRouteService;
import com.smsgateway.service.notify.SendResult;
import com.smsgateway.util.PageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息转发的管理端接口。
 *
 * <p>挂在 {@code /api/admin/**} 下，自动受 {@code AdminAuthInterceptor} 保护。
 *
 * <p>三个资源分开是刻意的：渠道（发到哪）、规则（哪些短信发过去）、投递记录（发出去了吗）。
 * 它们各自的改动频率与权限心智都不同 —— 混成一组接口会让人以为改渠道会影响已有规则。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/notify")
@RequiredArgsConstructor
public class AdminNotifyController {

    private final NotifyChannelService channelService;
    private final NotifyRouteService routeService;
    private final NotifyDeliveryService deliveryService;

    // ---------------------------------------------------------------- 渠道

    /**
     * 转发功能是否已配置好（后端启用了，且加密密钥有效）。
     *
     * <p>管理端进页面时先问一次，没配好就直接显示一条配置指引 ——
     * 否则使用者会填完整个渠道表单、点保存，才发现后端根本没启用转发。
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResult<Map<String, Object>>> status() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ready", channelService.isReady());
        return ResponseEntity.ok(ApiResult.success(payload));
    }

    /** 支持的渠道类型。给前端的选择器用 —— 让枚举成为唯一真源，前端不硬编码一份。 */
    @GetMapping("/channel/types")
    public ResponseEntity<ApiResult<List<Map<String, String>>>> channelTypes() {
        List<Map<String, String>> types = Arrays.stream(NotifyChannelType.values())
                .map(type -> Map.of("value", type.name(), "label", describeType(type)))
                .toList();
        return ResponseEntity.ok(ApiResult.success(types));
    }

    @GetMapping("/channel/list")
    public ResponseEntity<ApiResult<List<NotifyChannelView>>> channelList() {
        return ResponseEntity.ok(ApiResult.success(channelService.list()));
    }

    @PostMapping("/channel")
    public ResponseEntity<ApiResult<NotifyChannelView>> createChannel(
            @RequestBody NotifyChannelRequest request) {
        log.warn("Admin created notify channel: {}", request.getName());
        return ResponseEntity.ok(ApiResult.success(channelService.create(request)));
    }

    /** 修改渠道。{@code config} 留空表示不修改，见 {@link NotifyChannelRequest}。 */
    @PutMapping("/channel/{id}")
    public ResponseEntity<ApiResult<NotifyChannelView>> updateChannel(
            @PathVariable Long id,
            @RequestBody NotifyChannelRequest request) {
        return ResponseEntity.ok(ApiResult.success(channelService.update(id, request)));
    }

    @DeleteMapping("/channel/{id}")
    public ResponseEntity<ApiResult<Void>> deleteChannel(@PathVariable Long id) {
        log.warn("Admin deleted notify channel: {}", id);
        channelService.delete(id);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @PutMapping("/channel/{id}/enabled")
    public ResponseEntity<ApiResult<NotifyChannelView>> setChannelEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(channelService.setEnabled(id, enabled)));
    }

    /**
     * 发送一条测试消息。
     *
     * <p>配置期的必需能力：配好之后必须能立刻验证。否则要等到真有短信才知道配没配对，
     * 而那时用户正等着验证码。
     *
     * <p>测试内容**不含任何真实验证码**，用的是样例文本 —— 测试消息不该把真实凭据带出去。
     */
    @PostMapping("/channel/{id}/test")
    public ResponseEntity<ApiResult<Map<String, Object>>> testChannel(@PathVariable Long id) {
        SendResult result = channelService.test(id);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        payload.put("statusCode", result.statusCode() > 0 ? result.statusCode() : null);
        payload.put("detail", channelService.previewOf(result));

        return ResponseEntity.ok(ApiResult.success(payload));
    }

    // ---------------------------------------------------------------- 规则

    @GetMapping("/route/list")
    public ResponseEntity<ApiResult<List<NotifyRouteView>>> routeList() {
        return ResponseEntity.ok(ApiResult.success(routeService.list()));
    }

    @PostMapping("/route")
    public ResponseEntity<ApiResult<NotifyRouteView>> createRoute(
            @RequestBody NotifyRouteRequest request) {
        log.warn("Admin created notify route: {}", request.getRouteName());
        return ResponseEntity.ok(ApiResult.success(routeService.create(request)));
    }

    @PutMapping("/route/{id}")
    public ResponseEntity<ApiResult<NotifyRouteView>> updateRoute(
            @PathVariable Long id,
            @RequestBody NotifyRouteRequest request) {
        return ResponseEntity.ok(ApiResult.success(routeService.update(id, request)));
    }

    @DeleteMapping("/route/{id}")
    public ResponseEntity<ApiResult<Void>> deleteRoute(@PathVariable Long id) {
        routeService.delete(id);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @PutMapping("/route/{id}/enabled")
    public ResponseEntity<ApiResult<NotifyRouteView>> setRouteEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(routeService.setEnabled(id, enabled)));
    }

    // ---------------------------------------------------------------- 投递记录

    @GetMapping("/delivery/list")
    public ResponseEntity<ApiResult<PageResult<NotifyDeliveryView>>> deliveryList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResult.success(deliveryService.list(
                PageUtil.safePage(page), PageUtil.safePageSize(pageSize), channelId, status)));
    }

    /**
     * 手动重投。
     *
     * <p>退回待投递状态，**不在这里直接发** —— 直接发会绕过限流与同渠道串行，
     * 而那两样正是调度器存在的理由。
     */
    @PostMapping("/delivery/{id}/retry")
    public ResponseEntity<ApiResult<NotifyDeliveryView>> retryDelivery(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResult.success(deliveryService.retry(id)));
    }

    /**
     * 渠道类型的中文名。前端选择器直接用它，避免同一份文案在前后端各写一遍。
     *
     * <p>switch 是**穷尽式**的（没有 default）：{@code NotifyChannelType} 加一个值而这里
     * 忘了补，编译就会失败。加这一份文案的成本远低于「枚举加了、下拉里却没有」那种
     * 只在运行时才发现的漏。
     */
    private String describeType(NotifyChannelType type) {
        return switch (type) {
            case WECOM_BOT -> "企业微信群机器人";
            case FEISHU_BOT -> "飞书群机器人";
            case DINGTALK_BOT -> "钉钉群机器人";
            case TELEGRAM_BOT -> "Telegram Bot";
            case SLACK_WEBHOOK -> "Slack Webhook";
            case WXPUSHER -> "WxPusher（个人微信）";
            case SERVERCHAN -> "Server酱（个人微信）";
            case PUSHPLUS -> "PushPlus（个人微信）";
            case GENERIC_WEBHOOK -> "通用 Webhook";
            case WECOM_APP -> "企业微信应用消息";
        };
    }
}
