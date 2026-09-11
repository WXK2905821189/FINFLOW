package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 供应商在线配置（V28，单例行 id=1）：页面保存的 DB 配置逐字段覆盖
 * application.yml 的 ai.*（见 {@code AiConfigService} 合并语义）。
 * 密钥只存 AES-256-GCM 密文与尾 4 位 hint，明文永不落库、永不回显。
 */
@TableName("ai_provider_config")
public class AiProviderConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Boolean enabled;
    private String baseUrl;
    private String apiKeyCipher;
    private String apiKeyHint;
    private String model;
    private Integer timeoutMillis;
    private Integer maxRetries;
    private Integer dailyLimitPerUser;
    private String capabilitiesJson;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyCipher() { return apiKeyCipher; }
    public void setApiKeyCipher(String apiKeyCipher) { this.apiKeyCipher = apiKeyCipher; }
    public String getApiKeyHint() { return apiKeyHint; }
    public void setApiKeyHint(String apiKeyHint) { this.apiKeyHint = apiKeyHint; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(Integer timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public Integer getDailyLimitPerUser() { return dailyLimitPerUser; }
    public void setDailyLimitPerUser(Integer dailyLimitPerUser) { this.dailyLimitPerUser = dailyLimitPerUser; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
