package com.finance.system.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * AI 设置页保存请求（PUT /api/ai/config）。密钥语义：
 * <ul>
 *   <li>{@code apiKey == null} —— 保持现有密钥不变（页面留空即不发此字段）；</li>
 *   <li>{@code apiKey == ""} —— 清除已存密钥（回落 env 或视为未配置）；</li>
 *   <li>非空 —— 加密落库为新密钥。</li>
 * </ul>
 * 字段传 {@code null} = 该项不做 DB 覆盖（回落 env 值）。
 */
public record AiConfigUpsertRequest(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        @Min(value = 1000, message = "超时不能小于 1 秒")
        @Max(value = 300_000, message = "超时不能超过 5 分钟")
        Integer timeoutMillis,
        @Min(0) @Max(5) Integer maxRetries,
        Map<String, Boolean> capabilities
) {
}
