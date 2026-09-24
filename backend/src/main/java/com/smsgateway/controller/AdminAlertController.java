package com.smsgateway.controller;

import com.smsgateway.model.dto.AlertRuleRequest;
import com.smsgateway.model.dto.AlertRuleView;
import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.service.notify.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端：故障告警规则。
 *
 * <p>路径与命名刻意与 {@code AdminNotifyController} 的 route 段同构（那里是
 * {@code /api/admin/notify/route/**}，这里是 {@code /api/admin/alert/rule/**}），
 * 因为两者在界面上的位置与操作方式完全对称，管理端可以复用同一套表单组件。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/alert")
@RequiredArgsConstructor
public class AdminAlertController {

    private final AlertRuleService alertRuleService;

    @GetMapping("/rule/list")
    public ResponseEntity<ApiResult<List<AlertRuleView>>> list() {
        return ResponseEntity.ok(ApiResult.success(alertRuleService.list()));
    }

    /** 告警类型下拉。前端不硬编码映射（同 EventType 的做法）。 */
    @GetMapping("/rule/types")
    public ResponseEntity<ApiResult<List<Map<String, String>>>> types() {
        return ResponseEntity.ok(ApiResult.success(alertRuleService.types()));
    }

    @PostMapping("/rule")
    public ResponseEntity<ApiResult<AlertRuleView>> create(@Valid @RequestBody AlertRuleRequest request) {
        return ResponseEntity.ok(ApiResult.success(alertRuleService.create(request)));
    }

    @PutMapping("/rule/{id}")
    public ResponseEntity<ApiResult<AlertRuleView>> update(@PathVariable Long id,
                                                          @Valid @RequestBody AlertRuleRequest request) {
        return ResponseEntity.ok(ApiResult.success(alertRuleService.update(id, request)));
    }

    @DeleteMapping("/rule/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        alertRuleService.delete(id);
        log.warn("Admin deleted alert rule {}", id);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @PutMapping("/rule/{id}/enabled")
    public ResponseEntity<ApiResult<AlertRuleView>> setEnabled(@PathVariable Long id,
                                                               @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(alertRuleService.setEnabled(id, enabled)));
    }
}
