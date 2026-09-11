package com.finance.system.ai.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * GET/PUT /api/ai/config 响应。两层结构：
 * <ul>
 *   <li>{@code db} —— 在线配置现状（密钥只有 hint 尾 4 位与"是否已配置"）；</li>
 *   <li>{@code effective} —— 合并 env 后的生效快照（密钥同样只暴露布尔位）。</li>
 * </ul>
 */
public record AiConfigResponse(DbView db, EffectiveView effective) {

    /** DB 行的脱敏视图（行不存在时各字段为 null）。 */
    public record DbView(
            Boolean enabled,
            String baseUrl,
            String model,
            String apiKeyHint,
            boolean apiKeyConfigured,
            Integer timeoutMillis,
            Integer maxRetries,
            Integer dailyLimitPerUser,
            Map<String, Boolean> capabilities,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    /** 合并后的生效配置（与 /api/ai/status 同口径，另带来源说明）。 */
    public record EffectiveView(
            boolean enabled,
            String provider,
            String model,
            String baseUrl,
            boolean apiKeyConfigured,
            int dailyLimitPerUser,
            Map<String, Boolean> capabilities,
            String configSource) {
    }
}
