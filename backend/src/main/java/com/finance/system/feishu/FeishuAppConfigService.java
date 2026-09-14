package com.finance.system.feishu;

import com.finance.system.ai.AiSecretCipher;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.FeishuAppConfig;
import com.finance.system.domain.mapper.FeishuAppConfigMapper;
import com.finance.system.feishu.dto.FeishuAppConfigResponse;
import com.finance.system.feishu.dto.FeishuAppConfigUpsertRequest;
import com.finance.system.feishu.dto.FeishuAppConfigVerifyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 飞书自建应用凭证配置（V29）：页面连接向导的后端——凭证加密落库 + 真实验证。
 *
 * <p>安全口径与 V28 AI 密钥一致（复用 {@link AiSecretCipher}，AES-256-GCM，
 * 密钥派生自 app.jwt.secret）：明文永不落日志/响应，读接口只给尾 4 位 hint。
 * 验证 = 真实调飞书 {@code POST /open-apis/auth/v3/tenant_access_token/internal}，
 * 成功后尽力拉租户名（失败不阻塞验证结果）。凭证错误（飞书 code != 0）转 400
 * 可读提示；网络/5xx 转 502 带诊断信息，永不携带 Secret。</p>
 */
@Service
public class FeishuAppConfigService {

    private static final Logger log = LoggerFactory.getLogger(FeishuAppConfigService.class);
    private static final long ROW_ID = 1L;
    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String TENANT_QUERY_URL = "https://open.feishu.cn/open-apis/tenant/v2/tenant/query";

    private final FeishuAppConfigMapper mapper;
    private final AiSecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FeishuAppConfigService(FeishuAppConfigMapper mapper, AiSecretCipher cipher, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 脱敏视图：Secret 只回 hint 与布尔位。 */
    public FeishuAppConfigResponse view() {
        FeishuAppConfig row = mapper.selectById(ROW_ID);
        if (row == null) {
            return new FeishuAppConfigResponse(null, false, null, false, null, null, null, null);
        }
        return new FeishuAppConfigResponse(
                row.getAppId(),
                row.getAppSecretCipher() != null && !row.getAppSecretCipher().isBlank(),
                row.getAppSecretHint(),
                row.getVerifiedAt() != null,
                row.getVerifiedAt(),
                row.getVerifiedTenant(),
                row.getUpdatedAt(),
                row.getUpdatedBy());
    }

    /** 保存凭证：appSecret null=保持 / ""=清除 / 非空=换新（先加密再落库）。 */
    public FeishuAppConfigResponse upsert(Long userId, FeishuAppConfigUpsertRequest request) {
        FeishuAppConfig row = mapper.selectById(ROW_ID);
        if (row == null) {
            row = new FeishuAppConfig();
            row.setId(ROW_ID);
        }
        if (request.appId() != null) {
            row.setAppId(request.appId().isBlank() ? null : request.appId().trim());
        }
        if (request.appSecret() != null) {
            if (request.appSecret().isBlank()) {
                row.setAppSecretCipher(null);
                row.setAppSecretHint(null);
                row.setVerifiedAt(null);
                row.setVerifiedBy(null);
                row.setVerifiedTenant(null);
            } else {
                row.setAppSecretCipher(cipher.encrypt(request.appSecret().trim()));
                row.setAppSecretHint("****" + last4(request.appSecret().trim()));
                row.setVerifiedAt(null);
                row.setVerifiedBy(null);
                row.setVerifiedTenant(null);
            }
        }
        row.setUpdatedBy(userId);
        row.setUpdatedAt(LocalDateTime.now());
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(row.getUpdatedAt());
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return view();
    }

    /**
     * 真实验证：表单当前值优先（appSecret 留空回落已保存密钥），调飞书
     * tenant_access_token/internal；成功则落库凭证并记录验证人与租户名。
     */
    public FeishuAppConfigResponse verify(Long userId, FeishuAppConfigVerifyRequest request) {
        String appId = request.appId() != null && !request.appId().isBlank()
                ? request.appId().trim() : null;
        String secret = request.appSecret() != null && !request.appSecret().isBlank()
                ? request.appSecret().trim() : null;
        FeishuAppConfig saved = mapper.selectById(ROW_ID);
        if (appId == null && saved != null) {
            appId = saved.getAppId();
        }
        if (secret == null) {
            secret = decryptSaved(saved);
        }
        if (appId == null || appId.isBlank()) {
            throw new BusinessException(400, "请先填写 App ID（飞书开放平台 → 开发者后台 → 凭证与基础信息）");
        }
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(400, "请先填写 App Secret（留空仅当已保存过密钥）");
        }

        JsonNode tokenResponse = postJson(TOKEN_URL, Map.of("app_id", appId, "app_secret", secret));
        int code = tokenResponse.path("code").asInt(-1);
        if (code != 0) {
            throw new BusinessException(400, "飞书凭证校验未通过（code " + code + "）："
                    + tokenResponse.path("msg").asText("无错误信息")
                    + "。请核对 App ID / App Secret，并确认应用可用性未关闭");
        }
        String tenantAccessToken = tokenResponse.path("tenant_access_token").asText(null);
        if (tenantAccessToken == null || tenantAccessToken.isBlank()) {
            throw new BusinessException(502, "飞书响应缺少 tenant_access_token 字段（协议异常）");
        }
        String tenantName = queryTenantName(tenantAccessToken);

        // 验证通过 → 保存（或合并）凭证并记录验证元数据
        FeishuAppConfig row = saved == null ? new FeishuAppConfig() : saved;
        if (row.getId() == null) {
            row.setId(ROW_ID);
        }
        row.setAppId(appId);
        if (request.appSecret() != null && !request.appSecret().isBlank()) {
            row.setAppSecretCipher(cipher.encrypt(secret));
            row.setAppSecretHint("****" + last4(secret));
        }
        row.setVerifiedAt(LocalDateTime.now());
        row.setVerifiedBy(userId);
        row.setVerifiedTenant(tenantName);
        row.setUpdatedBy(userId);
        row.setUpdatedAt(row.getVerifiedAt());
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(row.getUpdatedAt());
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return view();
    }

    /** 已保存密文解密；解密失败按未配置处理（JWT secret 轮换场景，提示重新保存）。 */
    private String decryptSaved(FeishuAppConfig saved) {
        if (saved == null || saved.getAppSecretCipher() == null || saved.getAppSecretCipher().isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(saved.getAppSecretCipher());
        } catch (AiSecretCipher.AiSecretCipherException e) {
            throw new BusinessException(400, "已保存的密钥无法解密（服务端 JWT 密钥可能已轮换）：请重新填写 App Secret 再验证");
        }
    }

    /** 拉租户名（best-effort）：失败不阻塞验证，仅记 debug。 */
    private String queryTenantName(String tenantAccessToken) {
        try {
            JsonNode response = restClient.get()
                    .uri(TENANT_QUERY_URL)
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode name = response == null ? null : response.path("data").path("tenant").path("name");
            return name.isMissingNode() || name.isNull() ? null : name.asText();
        } catch (Exception e) {
            log.debug("feishu tenant name query failed (non-blocking): {}", e.toString());
            return null;
        }
    }

    private JsonNode postJson(String url, Map<String, String> body) {
        try {
            String responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(responseBody == null ? "" : responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new BusinessException(502, "飞书接口调用失败（HTTP " + e.getStatusCode().value() + "）："
                    + snippet(e.getResponseBodyAsString()));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new BusinessException(502, "飞书接口网络异常（" + e.getClass().getSimpleName()
                    + "）：服务器出网到 open.feishu.cn 可能被阻断，请检查出口白名单");
        } catch (Exception e) {
            throw new BusinessException(502, "飞书接口响应解析失败：" + e.getClass().getSimpleName());
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "无响应体";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static String last4(String secret) {
        return secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
    }
}
