package com.finance.system.rbac.dto;

import jakarta.validation.constraints.NotBlank;

/** V45：PUT /api/users/{id}/permission-overrides 请求体元素。 */
public record UserPermissionOverrideItemRequest(
        @NotBlank String code,
        @NotBlank String effect) {
}
