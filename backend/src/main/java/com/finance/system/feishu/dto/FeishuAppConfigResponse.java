package com.finance.system.feishu.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 飞书自建应用配置视图（脱敏）：Secret 永不回显，只给 hint 与布尔位。
 */
public record FeishuAppConfigResponse(
        @Schema(description = "App ID（明文，非敏感）") String appId,
        @Schema(description = "App Secret 是否已配置") boolean secretConfigured,
        @Schema(description = "Secret 尾 4 位提示（如 ****x1y2），未配置为 null") String secretHint,
        @Schema(description = "是否已通过飞书真实验证") boolean verified,
        @Schema(description = "最近验证时间") LocalDateTime verifiedAt,
        @Schema(description = "验证通过的租户名（拉取失败为 null）") String verifiedTenant,
        @Schema(description = "最近保存时间") LocalDateTime updatedAt,
        @Schema(description = "最近保存人") Long updatedBy) {
}
