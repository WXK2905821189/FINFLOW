package com.finance.system.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.auth.AuthService;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.rbac.UserPermissionOverrideService;
import com.finance.system.rbac.dto.UserPermissionOverrideItem;
import com.finance.system.rbac.dto.UserPermissionOverrideItemRequest;
import com.finance.system.rbac.dto.UserPermissionOverrideResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.user.dto.UserUpsertRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final UserPermissionOverrideService permissionOverrideService;

    public UserController(SysUserService userService, AuthService authService,
                          UserPermissionOverrideService permissionOverrideService) {
        this.userService = userService;
        this.authService = authService;
        this.permissionOverrideService = permissionOverrideService;
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
    public ApiResponse<CurrentUserResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody UserUpsertRequest request) {
        return ApiResponse.success("User created", authService.currentUser(userService.create(principal.getId(), request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Update a user and its roles")
    public ApiResponse<CurrentUserResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody UserUpsertRequest request) {
        return ApiResponse.success("User updated", authService.currentUser(userService.updateUser(principal.getId(), id, request)));
    }

    /** V36-W5（需求5）：物理删除（V34 拍板③）。有业务/审计引用时 409 提示改用「停用」。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Physically delete a user; rejected when business/audit references exist")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        userService.delete(principal.getId(), id);
        return ApiResponse.success("User deleted", null);
    }

    /** V45（W17 包 D）：账号级权限覆盖（复用 role:manage 权限点保护，不新建权限点）。 */
    @GetMapping("/{id}/permission-overrides")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "List account-level permission overrides (V45)")
    public ApiResponse<UserPermissionOverrideResponse> listPermissionOverrides(@PathVariable Long id) {
        return ApiResponse.success(new UserPermissionOverrideResponse(id, permissionOverrideService.listForUser(id)));
    }

    /** V45：全量替换覆盖（body: [{code, effect}]）；校验/互斥/防自锁规则见 UserPermissionOverrideService。 */
    @PutMapping("/{id}/permission-overrides")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "Replace account-level permission overrides (V45)")
    public ApiResponse<UserPermissionOverrideResponse> replacePermissionOverrides(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody List<UserPermissionOverrideItemRequest> request) {
        List<UserPermissionOverrideItem> overrides = permissionOverrideService.replaceAll(principal.getId(), id, request);
        return ApiResponse.success("Permission overrides updated", new UserPermissionOverrideResponse(id, overrides));
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizeSize(long size) {
        return Math.min(100, Math.max(1, size));
    }
}
