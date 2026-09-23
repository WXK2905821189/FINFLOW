package com.finance.system.rbac.dto;

import java.util.List;

/** V45：GET /api/users/{id}/permission-overrides 响应（当前覆盖全量）。 */
public record UserPermissionOverrideResponse(Long userId, List<UserPermissionOverrideItem> overrides) {
}
