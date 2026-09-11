package com.finance.system.ai;

import com.finance.system.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * OpenAI 兼容 LLM 客户端（P0 地基，~200 行量级，不上 Spring AI）。
 *
 * <p>协议：POST {base-url}/chat/completions，Bearer 密钥（环境变量注入）。
 * 策略：连接/读超时独立配置；5xx 与网络异常按 {@code maxRetries} 短退避重试；
 * 4xx 视为调用方错误不重试。所有异常统一翻译为 {@link BusinessException}(502)，
 * 消息带异常类名与 HTTP 状态（诊断不依赖容器日志），但<b>永不携带密钥</b>。</p>
 */
@Component
public class OpenAiCompatibleLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmGateway.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleLlmGateway(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMillis());
        factory.setReadTimeout(properties.getTimeoutMillis());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** fail-fast：总开关打开但密钥缺失时拒绝启动（密钥只能来自环境变量）。 */
    @PostConstruct
    void validateConfiguration() {
        if (properties.isEnabled() && (properties.getApiKey() == null || properties.getApiKey().isBlank())) {
            throw new IllegalStateException(
                    "ai.enabled=true 但 ai.api-key 为空：请通过环境变量 AI_API_KEY 注入密钥（拒绝把密钥写进配置文件）");
        }
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        long startedAt = System.currentTimeMillis();
        int attempts = Math.max(0, properties.getMaxRetries()) + 1;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return doChat(request, startedAt);
            } catch (RestClientResponseException e) {
                lastFailure = e;
                if (e.getStatusCode().is5xxServerError() && attempt < attempts) {
                    sleep(attempt);
                    continue;
                }
                break;
            } catch (ResourceAccessException e) {
                lastFailure = e;
                if (attempt < attempts) {
                    sleep(attempt);
                    continue;
                }
                break;
            }
        }
        throw translate(lastFailure);
    }

    private LlmChatResult doChat(LlmChatRequest request, long startedAt) {
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", new Object[]{
                        Map.of("role", "system", "content", nullSafe(request.systemPrompt())),
                        Map.of("role", "user", "content", nullSafe(request.userPrompt()))
                },
                "temperature", request.temperature() == null ? 0.2 : request.temperature(),
                "max_tokens", request.maxTokens() == null ? 1024 : request.maxTokens());
        String responseBody = restClient.post()
                .uri(properties.getBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parse(responseBody, startedAt);
    }

    private LlmChatResult parse(String responseBody, long startedAt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new BusinessException(502, "LLM 响应缺少 choices 字段（OpenAI 兼容协议）");
            }
            String content = choices.get(0).path("message").path("content").asText(null);
            if (content == null) {
                throw new BusinessException(502, "LLM 响应缺少 message.content 字段");
            }
            JsonNode usage = root.path("usage");
            return new LlmChatResult(
                    content,
                    root.path("model").asText(properties.getModel()),
                    usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null,
                    usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null,
                    System.currentTimeMillis() - startedAt);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "LLM 响应解析失败：" + e.getClass().getSimpleName()
                    + "（响应非 OpenAI 兼容 JSON）");
        }
    }

    /** 异常翻译：诊断信息充足（类名 + 状态 + 响应摘要），密钥绝不进消息。 */
    private BusinessException translate(Exception failure) {
        if (failure instanceof RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String snippet = e.getResponseBodyAsString();
            if (snippet != null && snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "...";
            }
            return new BusinessException(502, "LLM 调用失败（HTTP " + status.value() + "，"
                    + e.getClass().getSimpleName() + "）："
                    + (snippet == null || snippet.isBlank() ? "无响应体" : snippet));
        }
        if (failure instanceof ResourceAccessException e) {
            return new BusinessException(502, "LLM 网络异常（" + e.getClass().getSimpleName()
                    + "）：接入点不可达或超时，请检查 ai.base-url 与出网白名单");
        }
        return new BusinessException(502, "LLM 调用异常（" + failure.getClass().getSimpleName() + "）");
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
