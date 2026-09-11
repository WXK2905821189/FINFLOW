package com.finance.system.ai.dto;

import java.util.Map;

/**
 * AI 网关状态（管理面展示）：密钥永不出现——只暴露「是否已配置」布尔位。
 */
public record AiStatusResponse(boolean enabled, String provider, String model,
                               String baseUrl, boolean apiKeyConfigured,
                               int dailyLimitPerUser,
                               Map<String, Boolean> capabilities) {
}
