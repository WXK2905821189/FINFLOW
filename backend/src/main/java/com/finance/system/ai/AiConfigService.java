package com.finance.system.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.dto.AiConfigResponse;
import com.finance.system.ai.dto.AiConfigUpsertRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AiProviderConfig;
import com.finance.system.domain.mapper.AiProviderConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI 在线配置（V28）：单例行 id=1，DB 逐字段覆盖 env（application.yml ai.*）。
 *
 * <p>合并语义（{@link #effective}）：行不存在 = 纯 env（V27 行为不变）；
 * 行存在时 enabled 总以 DB 为准，其余字段 DB 非空才覆盖，capabilities 按 key 合并。
 * 密钥解密失败（JWT secret 轮换）不阻断系统——按「未配置密钥」处理并给可操作提示。</p>
 */
@Service
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);
    private static final long SINGLETON_ID = 1L;

    private final AiProviderConfigMapper mapper;
    private final AiProperties properties;
    private final AiSecretCipher cipher;
    private final ObjectMapper objectMapper;

    public AiConfigService(AiProviderConfigMapper mapper, AiProperties properties,
                           AiSecretCipher cipher, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.properties = properties;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /** 合并 env 后的生效配置（每次调用实时读库，保存即生效、无需重启）。 */
    public AiEffectiveConfig effective() {
        return effective(null, null, null);
    }

    /**
     * 生效配置 + 临时覆盖（连通性测试用：表单未保存的 base-url/api-key/model
     * 只在本次请求内存中生效）。
     */
    public AiEffectiveConfig effective(String overrideBaseUrl, String overrideApiKey, String overrideModel) {
        AiProviderConfig row = row();
        boolean enabled = row != null && Boolean.TRUE.equals(row.getEnabled())
                || (row == null && properties.isEnabled());
        String baseUrl = firstNonBlank(overrideBaseUrl,
                row == null ? null : row.getBaseUrl(), properties.getBaseUrl());
        String apiKey = firstNonBlank(overrideApiKey, decryptedKey(row), properties.getApiKey());
        String model = firstNonBlank(overrideModel,
                row == null ? null : row.getModel(), properties.getModel());
        int timeout = row != null && row.getTimeoutMillis() != null ? row.getTimeoutMillis() : properties.getTimeoutMillis();
        int retries = row != null && row.getMaxRetries() != null ? row.getMaxRetries() : properties.getMaxRetries();
        int limit = row != null && row.getDailyLimitPerUser() != null
                ? row.getDailyLimitPerUser() : properties.getDailyLimitPerUser();
        Map<String, Boolean> capabilities = new LinkedHashMap<>(properties.getCapabilities());
        if (row != null && row.getCapabilitiesJson() != null && !row.getCapabilitiesJson().isBlank()) {
            try {
                capabilities.putAll(objectMapper.readValue(row.getCapabilitiesJson(),
                        new TypeReference<Map<String, Boolean>>() { }));
            } catch (Exception e) {
                log.warn("ai_provider_config.capabilities_json 解析失败，回落 env 能力开关：{}", e.getMessage());
            }
        }
        String source = row == null ? "环境变量" : "在线配置";
        return new AiEffectiveConfig(enabled, baseUrl, apiKey, model, timeout, retries, limit,
                capabilities, properties.getProvider(), source);
    }

    /** 设置页读视图：DB 行脱敏 + 生效快照。 */
    public AiConfigResponse view() {
        AiProviderConfig row = row();
        AiConfigResponse.DbView db = row == null ? null : new AiConfigResponse.DbView(
                row.getEnabled(), row.getBaseUrl(), row.getModel(), row.getApiKeyHint(),
                row.getApiKeyCipher() != null && !row.getApiKeyCipher().isBlank(),
                row.getTimeoutMillis(), row.getMaxRetries(), row.getDailyLimitPerUser(),
                parseCapabilities(row), row.getUpdatedAt(), row.getUpdatedBy());
        AiEffectiveConfig effective = effective();
        AiConfigResponse.EffectiveView effectiveView = new AiConfigResponse.EffectiveView(
                effective.enabled(), effective.provider(), effective.model(), effective.baseUrl(),
                effective.apiKeyConfigured(), effective.dailyLimitPerUser(), effective.capabilities(),
                effective.configSource());
        return new AiConfigResponse(db, effectiveView);
    }

    /** 保存（upsert 单例行）。保存前做「启用但无密钥」护栏。 */
    public AiConfigResponse upsert(AiConfigUpsertRequest request, Long operatorId) {
        AiProviderConfig row = row();
        boolean rowExists = row != null;
        if (row == null) {
            row = new AiProviderConfig();
            row.setId(SINGLETON_ID);
            row.setEnabled(false);
            row.setCreatedAt(LocalDateTime.now());
        }

        // 密钥先落内存候选，校验通过才写库
        String candidateCipher = row.getApiKeyCipher();
        String candidateHint = row.getApiKeyHint();
        if (request.apiKey() != null) {
            if (request.apiKey().isBlank()) {
                candidateCipher = null;
                candidateHint = null;
            } else {
                candidateCipher = cipher.encrypt(request.apiKey().trim());
                candidateHint = mask(request.apiKey().trim());
            }
        }

        boolean nextEnabled = request.enabled() != null ? request.enabled() : row.getEnabled();
        String effectiveKey = candidateCipher != null ? cipher.decrypt(candidateCipher) : properties.getApiKey();
        if (Boolean.TRUE.equals(nextEnabled)
                && (effectiveKey == null || effectiveKey.isBlank())) {
            throw new BusinessException(400, "启用 AI 前必须先配置 API 密钥（env 未提供且本次保存未填写）");
        }

        row.setEnabled(nextEnabled);
        if (request.baseUrl() != null) {
            row.setBaseUrl(trimToNull(request.baseUrl()));
        }
        row.setApiKeyCipher(candidateCipher);
        row.setApiKeyHint(candidateHint);
        if (request.model() != null) {
            row.setModel(trimToNull(request.model()));
        }
        if (request.timeoutMillis() != null) {
            row.setTimeoutMillis(request.timeoutMillis());
        }
        if (request.maxRetries() != null) {
            row.setMaxRetries(request.maxRetries());
        }
        if (request.dailyLimitPerUser() != null) {
            row.setDailyLimitPerUser(request.dailyLimitPerUser());
        }
        if (request.capabilities() != null) {
            row.setCapabilitiesJson(writeCapabilities(request.capabilities()));
        }
        row.setUpdatedBy(operatorId);
        row.setUpdatedAt(LocalDateTime.now());

        if (rowExists) {
            mapper.updateById(row);
        } else {
            mapper.insert(row);
        }
        log.info("AI 在线配置已更新 operatorId={} enabled={}", operatorId, row.getEnabled());
        return view();
    }

    private AiProviderConfig row() {
        return mapper.selectById(SINGLETON_ID);
    }

    /** 解密 DB 密文；轮换密钥等导致的解密失败按未配置处理（可操作提示）。 */
    private String decryptedKey(AiProviderConfig row) {
        if (row == null || row.getApiKeyCipher() == null || row.getApiKeyCipher().isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(row.getApiKeyCipher());
        } catch (AiSecretCipher.AiSecretCipherException e) {
            log.warn("AI 密钥密文解密失败（app.jwt.secret 可能已轮换），请到 AI 设置页重新保存密钥");
            return null;
        }
    }

    private Map<String, Boolean> parseCapabilities(AiProviderConfig row) {
        if (row.getCapabilitiesJson() == null || row.getCapabilitiesJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getCapabilitiesJson(), new TypeReference<Map<String, Boolean>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private String writeCapabilities(Map<String, Boolean> capabilities) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(capabilities));
        } catch (Exception e) {
            throw new BusinessException(400, "能力开关序列化失败：" + e.getClass().getSimpleName());
        }
    }

    private static String mask(String apiKey) {
        String trimmed = apiKey.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
