package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.CollectRuleRequest;
import com.smsgateway.model.entity.SmsCollectRule;
import com.smsgateway.service.AdminRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin/rule")
@RequiredArgsConstructor
public class AdminRuleController {

    private final AdminRuleService adminRuleService;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<List<SmsCollectRule>>> list() {
        return ResponseEntity.ok(ApiResult.success(adminRuleService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResult<SmsCollectRule>> create(@Valid @RequestBody CollectRuleRequest request) {
        return ResponseEntity.ok(ApiResult.success(adminRuleService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<SmsCollectRule>> update(@PathVariable Long id,
                                                            @Valid @RequestBody CollectRuleRequest request) {
        return ResponseEntity.ok(ApiResult.success(adminRuleService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        adminRuleService.delete(id);
        return ResponseEntity.ok(ApiResult.success("deleted", null));
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<ApiResult<SmsCollectRule>> setEnabled(@PathVariable Long id,
                                                                @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(adminRuleService.setEnabled(id, enabled)));
    }
}
