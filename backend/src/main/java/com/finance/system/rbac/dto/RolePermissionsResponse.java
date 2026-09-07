package com.finance.system.rbac.dto;

import java.util.List;

/**
 * Role shape for GET /api/rbac/roles: carries the permission id set so the admin UI can
 * initialize the role editor checkboxes without a second round trip per role.
 */
public record RolePermissionsResponse(
        Long id,
        String code,
        String name,
        String description,
        List<Long> permissionIds
) {
}
