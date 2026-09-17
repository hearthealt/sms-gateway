package com.smsgateway.controller;

import com.smsgateway.model.dto.ApiResult;
import com.smsgateway.model.dto.LoginRequest;
import com.smsgateway.model.dto.LoginResponse;
import com.smsgateway.service.AdminAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ResponseEntity<ApiResult<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Admin login request: username={}", request.getUsername());
        return ResponseEntity.ok(ApiResult.success(adminAuthService.login(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(@RequestAttribute(value = "adminToken", required = false) String token) {
        adminAuthService.logout(token);
        return ResponseEntity.ok(ApiResult.success("logged out", null));
    }
}
