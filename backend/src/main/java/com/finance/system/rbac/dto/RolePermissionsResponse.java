package com.finance.system.rbac.dto;

import java.util.List;

/**
 * Role shape for GET /api/rbac/roles: carries the permission id set so the admin UI can
 * initialize the role editor checkboxes without a second round trip per role, plus the
 * number of accounts holding the role (V33 可视化权限管理：编辑前先看影响面).
 */
public record RolePermissionsResponse(
        Long id,
        String code,
        String name,
        String description,
        List<Long> permissionIds,
        long userCount
) {
}
