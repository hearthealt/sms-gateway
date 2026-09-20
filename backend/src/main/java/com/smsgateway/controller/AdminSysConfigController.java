package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.SysConfigRequest;
import com.smsgateway.model.dto.SysConfigView;
import com.smsgateway.model.enums.SysConfigKey;
import com.smsgateway.service.SysConfigService;
import com.smsgateway.service.notify.NotifyCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统设置。挂在 {@code /api/admin/**} 下，自动受 {@code AdminAuthInterceptor} 保护。
 *
 * <p>只暴露 {@code SysConfigKey} 里列出的键 —— 不认识的一律拒绝。
 * 否则这张表就成了一个「什么都能往里写」的口袋，而读的地方只认自己那几个键，
 * 写进去的东西悄无声息地不生效。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sysconfig")
@RequiredArgsConstructor
public class AdminSysConfigController {

    private final SysConfigService sysConfigService;
    private final NotifyCrypto notifyCrypto;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<List<SysConfigView>>> list() {
        List<SysConfigView> views = sysConfigService.all().entrySet().stream()
                .map(entry -> {
                    SysConfigKey key = entry.getKey();
                    return new SysConfigView(
                            key.key(),
                            entry.getValue(),
                            key.defaultValue(),
                            key.type().name(),
                            key.label(),
                            key.description(),
                            key.group().label());
                })
                .toList();
        return ResponseEntity.ok(ApiResult.success(views));
    }

    /**
     * 改一项配置，**立即生效，不用重启**。
     *
     * <p>值不合法时抛 {@code IllegalArgumentException} → 400 + 原消息（如
     * 「短信保留天数：不能是负数」）—— 而不是存进去之后让某个功能行为诡异。
     */
    @PutMapping
    public ResponseEntity<ApiResult<Void>> update(@RequestBody SysConfigRequest request) {
        SysConfigKey key = SysConfigKey.byKey(request.getKey());
        if (key == null) {
            throw new IllegalArgumentException("不认识的配置项：" + request.getKey());
        }

        // 打开转发总开关之前先确认加密密钥在。
        //
        // 跳这一步的话，界面上会先显示「已启用」（库里确实存下了），使用者以为功能开了，
        // 直到他去配第一个渠道时才报错 —— 而那时他已经填完了整个表单。
        // 把关口提到打开开关这一刻，报错就出现在「刚做了那个动作」的上下文里。
        if (key == SysConfigKey.NOTIFY_ENABLED && Boolean.parseBoolean(request.getValue())) {
            notifyCrypto.requireReady();
        }

        sysConfigService.set(key, request.getValue());
        return ResponseEntity.ok(ApiResult.success(null));
    }
}
