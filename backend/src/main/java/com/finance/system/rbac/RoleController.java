package com.finance.system.rbac;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.domain.entity.SysPermission;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.rbac.dto.RolePermissionsResponse;
import com.finance.system.rbac.dto.RoleRequest;
import com.finance.system.rbac.dto.RoleUpdateRequest;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rbac")
@SecurityRequirement(name = "bearerAuth")
public class RoleController {

    private final RbacService rbacService;

    public RoleController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "List roles with their permission id sets")
    public ApiResponse<List<RolePermissionsResponse>> roles() {
        return ApiResponse.success(rbacService.listRolesWithPermissions());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "List permissions")
    public ApiResponse<List<SysPermission>> permissions() {
        return ApiResponse.success(rbacService.listPermissions());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "Create a role with permissions")
    public ApiResponse<SysRole> createRole(@AuthenticationPrincipal UserPrincipal principal,
                                           @Valid @RequestBody RoleRequest request) {
        return ApiResponse.success("Role created", rbacService.createRole(principal.getId(), request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "Update a custom role's name and permission set (built-in roles are immutable)")
    public ApiResponse<SysRole> updateRole(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody RoleUpdateRequest request) {
        return ApiResponse.success("Role updated", rbacService.updateRole(principal.getId(), id, request));
    }
}
