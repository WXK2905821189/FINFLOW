package com.finance.system.rbac;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.domain.entity.SysPermission;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.service.RbacService;
import com.finance.system.rbac.dto.RoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    @Operation(summary = "List roles")
    public ApiResponse<List<SysRole>> roles() {
        return ApiResponse.success(rbacService.listRoles());
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
    public ApiResponse<SysRole> createRole(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.success("Role created", rbacService.createRole(request));
    }
}
