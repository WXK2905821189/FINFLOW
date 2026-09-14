package com.finance.system.feishu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 飞书自建应用配置保存请求。
 *
 * <p>appSecret 语义与 AI 设置一致：null = 保持现有密钥不变；"" = 清除；
 * 非空 = 换新密钥（加密落库，明文永不回显）。</p>
 */
public record FeishuAppConfigUpsertRequest(
        @Schema(description = "飞书自建应用 App ID（cli_xxx）") @Size(max = 128) String appId,
        @Schema(description = "App Secret：null=保持 / \"\"=清除 / 非空=换新") @Size(max = 256) String appSecret) {
}
