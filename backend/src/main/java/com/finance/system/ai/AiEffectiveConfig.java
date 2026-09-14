package com.finance.system.ai;

import java.util.Map;

/**
 * 合并后的生效 AI 配置（V28）：DB 在线配置逐字段覆盖 application.yml 的 ai.* 后的
 * 最终快照。所有 AI 调用与守卫只认这份快照，不再直接读 {@link AiProperties}。
 *
 * @param configSource 配置来源说明（"环境变量" / "在线配置"），仅展示用
 */
public record AiEffectiveConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutMillis,
        int maxRetries,
        Map<String, Boolean> capabilities,
        String provider,
        String configSource) {

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isCapabilityEnabled(String capability) {
        return Boolean.TRUE.equals(capabilities.get(capability));
    }
}
