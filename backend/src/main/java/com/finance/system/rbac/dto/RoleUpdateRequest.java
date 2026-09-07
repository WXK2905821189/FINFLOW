package com.finance.system.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload for PUT /api/rbac/roles/{id}. The role code is immutable by design —
 * it is referenced by permission grants and bootstrap data, and renaming codes
 * silently changes authorization semantics.
 */
public record RoleUpdateRequest(
        @NotBlank(message = "Role name is required") @Size(max = 64) String name,
        @Size(max = 255) String description,
        @NotEmpty(message = "At least one permission is required") List<Long> permissionIds
) {
}
