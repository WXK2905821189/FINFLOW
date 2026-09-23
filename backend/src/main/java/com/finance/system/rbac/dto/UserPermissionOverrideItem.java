package com.finance.system.rbac.dto;

/** V45：账号权限覆盖条目（服务端返回 / 全量替换请求体元素）。 */
public record UserPermissionOverrideItem(String code, String effect) {
}
