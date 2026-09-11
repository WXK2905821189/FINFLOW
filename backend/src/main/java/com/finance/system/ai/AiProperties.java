package com.finance.system.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 能力地基（V27）配置。设计原则（规划文档第四节）：
 *
 * <ul>
 *   <li><b>默认全关（fail-closed）</b>：{@code ai.enabled=false} 且每个能力开关独立为 false，
 *       关掉后所有 AI 端点 403；</li>
 *   <li><b>密钥只走环境变量</b>：{@code AI_API_KEY}，绝不出现在代码/日志/前端/审计表
 *       （CI release-contract 扫描凭据字面量）；</li>
 *   <li><b>换供应商只改配置</b>：OpenAI 兼容协议（DeepSeek/通义/本地 vLLM 均兼容），
 *       改 {@code ai.base-url} + {@code ai.model} 即切换，零代码改动。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 网关总开关：false 时所有 AI 端点 403，且不要求配置密钥。 */
    private boolean enabled = false;

    /** OpenAI 兼容接入点（DeepSeek: https://api.deepseek.com）。 */
    private String baseUrl = "https://api.deepseek.com";

    /** API 密钥（环境变量 AI_API_KEY 注入；enabled=true 且为空时启动快速失败）。 */
    private String apiKey = "";

    /** 模型名（DeepSeek: deepseek-chat）。 */
    private String model = "deepseek-chat";

    /** 单次请求超时（毫秒）。 */
    private int timeoutMillis = 30_000;

    /** 5xx/网络异常时的额外重试次数（0=不重试）。 */
    private int maxRetries = 1;

    /** 每用户每能力每日调用上限（失败调用也计入，防滥用）。 */
    private int dailyLimitPerUser = 20;

    /** 能力开关：capability 名 → 是否开放，未登记的一律关闭。 */
    private Map<String, Boolean> capabilities = new HashMap<>();

    /** 供审计与自检展示的提供方标识（openai-compatible）。 */
    private String provider = "openai-compatible";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getDailyLimitPerUser() { return dailyLimitPerUser; }
    public void setDailyLimitPerUser(int dailyLimitPerUser) { this.dailyLimitPerUser = dailyLimitPerUser; }
    public Map<String, Boolean> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Boolean> capabilities) { this.capabilities = capabilities; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public boolean isCapabilityEnabled(String capability) {
        return Boolean.TRUE.equals(capabilities.get(capability));
    }
}
