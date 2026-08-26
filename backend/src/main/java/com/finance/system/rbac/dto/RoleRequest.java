package com.finance.system.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleRequest(
        @NotBlank(message = "Role code is required") @Size(max = 64) String code,
        @NotBlank(message = "Role name is required") @Size(max = 64) String name,
        @Size(max = 255) String description,
        @NotEmpty(message = "At least one permission is required") List<Long> permissionIds
) {
}
