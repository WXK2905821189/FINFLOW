package com.finance.system.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.auth.AuthService;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.user.dto.UserUpsertRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final SysUserService userService;
    private final AuthService authService;

    public UserController(SysUserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "List users")
    public ApiResponse<PageResponse<CurrentUserResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<com.finance.system.domain.entity.SysUser> results = userService.page(new Page<>(normalizePage(page), normalizeSize(size)));
        PageResponse<CurrentUserResponse> payload = new PageResponse<>(results.getCurrent(), results.getSize(), results.getTotal(),
                results.getRecords().stream().map(authService::currentUser).toList());
        return ApiResponse.success(payload);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Get a user")
    public ApiResponse<CurrentUserResponse> get(@PathVariable Long id) {
        var user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        return ApiResponse.success(authService.currentUser(user));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Create an active user")
    public ApiResponse<CurrentUserResponse> create(@Valid @RequestBody UserUpsertRequest request) {
        return ApiResponse.success("User created", authService.currentUser(userService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Update a user and its roles")
    public ApiResponse<CurrentUserResponse> update(@PathVariable Long id, @Valid @RequestBody UserUpsertRequest request) {
        return ApiResponse.success("User updated", authService.currentUser(userService.updateUser(id, request)));
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizeSize(long size) {
        return Math.min(100, Math.max(1, size));
    }
}
