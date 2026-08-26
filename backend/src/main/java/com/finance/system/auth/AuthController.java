package com.finance.system.auth;

import com.finance.system.auth.dto.AuthTokenResponse;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.auth.dto.LoginRequest;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/register")
    @Operation(summary = "Create a pending user account")
    public ApiResponse<CurrentUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success("Registration is pending activation", authService.register(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current authenticated user")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(authService.currentUser(principal));
    }
}
