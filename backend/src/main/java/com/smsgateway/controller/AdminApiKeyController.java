package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiKeyRequest;
import com.smsgateway.model.dto.ApiKeyView;
import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外部调用方密钥的管理端接口。
 * 密钥数量少（一个调用方一个），列表不做分页，与规则管理的 /list 保持一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/apikey")
@RequiredArgsConstructor
public class AdminApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping("/list")
    public ResponseEntity<ApiResult<List<ApiKeyView>>> list() {
        return ResponseEntity.ok(ApiResult.success(apiKeyService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResult<ApiKeyView>> create(@Valid @RequestBody ApiKeyRequest request) {
        return ResponseEntity.ok(ApiResult.success(apiKeyService.create(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        apiKeyService.delete(id);
        return ResponseEntity.ok(ApiResult.success("deleted", null));
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<ApiResult<ApiKeyView>> setEnabled(@PathVariable Long id,
                                                            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResult.success(apiKeyService.setEnabled(id, enabled)));
    }
}
