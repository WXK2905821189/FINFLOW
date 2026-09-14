package com.finance.system.feishu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 飞书连接验证请求：用表单当前值（可未保存）。appSecret 留空 = 用已保存的密钥。
 */
public record FeishuAppConfigVerifyRequest(
        @Schema(description = "App ID（留空用已保存值）") @Size(max = 128) String appId,
        @Schema(description = "App Secret（留空用已保存密钥）") @Size(max = 256) String appSecret) {
}
