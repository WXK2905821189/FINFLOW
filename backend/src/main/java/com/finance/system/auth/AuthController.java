package com.finance.system.auth;

import com.finance.system.auth.dto.AuthTokenResponse;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.auth.dto.LoginRequest;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in and receive a JWT")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 自注册已关闭（P0 2026-09-02）：匿名不可访问（SecurityConfig 已摘除 permitAll）。
     * 保留给具备 user:manage 的管理员代为创建 PENDING 账号；一般建号走 POST /api/users（直接 ACTIVE）。
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Create a pending user account (admin only, self-registration closed)")
    public ApiResponse<CurrentUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success("Registration is pending activation", authService.register(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current authenticated user")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(authService.currentUser(principal));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current JWT session")
    public ApiResponse<Void> logout(@AuthenticationPrincipal UserPrincipal principal,
                                    @RequestHeader("Authorization") String authorization) {
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        authService.logout(principal, token);
        return ApiResponse.success("Signed out", null);
    }
}
