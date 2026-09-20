package com.finance.system.ai;

import com.finance.system.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 兼容 LLM 客户端（P0 地基，~200 行量级，不上 Spring AI）。
 *
 * <p>协议：POST {base-url}/chat/completions，Bearer 密钥。V28 起配置来自调用方传入的
 * {@link AiEffectiveConfig}（DB 在线配置覆盖 env），保存即生效；RestClient 按超时值
 * 缓存复用（超时改了才重建）。策略：5xx 与网络异常按 {@code maxRetries} 短退避重试；
 * 4xx 视为调用方错误不重试。所有异常统一翻译为 {@link BusinessException}(502)，
 * 消息带异常类名与 HTTP 状态（诊断不依赖容器日志），但<b>永不携带密钥</b>。</p>
 */
@Component
public class OpenAiCompatibleLlmGateway implements LlmGateway {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Integer, RestClient> clientsByTimeout = new ConcurrentHashMap<>();

    public OpenAiCompatibleLlmGateway(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request, AiEffectiveConfig config) {
        long startedAt = System.currentTimeMillis();
        int attempts = Math.max(0, config.maxRetries()) + 1;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return doChat(request, config, startedAt);
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

    private LlmChatResult doChat(LlmChatRequest request, AiEffectiveConfig config, long startedAt) {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "messages", new Object[]{
                        Map.of("role", "system", "content", nullSafe(request.systemPrompt())),
                        Map.of("role", "user", "content", nullSafe(request.userPrompt()))
                },
                "temperature", request.temperature() == null ? 0.2 : request.temperature(),
                "max_tokens", request.maxTokens() == null ? 1024 : request.maxTokens());
        String responseBody = client(config.timeoutMillis()).post()
                .uri(config.baseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parse(responseBody, config, startedAt);
    }

    /** 按超时值缓存 RestClient（超时配置变更时才重建底层工厂）。 */
    private RestClient client(int timeoutMillis) {
        return clientsByTimeout.computeIfAbsent(timeoutMillis, timeout -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            return RestClient.builder()
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        });
    }

    @Override
    public List<String> listModels(AiEffectiveConfig config) {
        String responseBody = null;
        try {
            responseBody = client(config.timeoutMillis()).get()
                    .uri(config.baseUrl() + "/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .retrieve()
                    .body(String.class);
            JsonNode data = objectMapper.readTree(responseBody == null ? "" : responseBody).path("data");
            if (!data.isArray()) {
                throw new BusinessException(502, "模型列表响应缺少 data 字段（OpenAI 兼容协议 GET /models）");
            }
            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    models.add(id);
                }
            }
            if (models.isEmpty()) {
                throw new BusinessException(502, "接入点返回了空模型列表（请确认 Base URL 与密钥有效）");
            }
            return models;
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw translate(e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "模型列表解析失败：" + e.getClass().getSimpleName()
                    + "（响应非 JSON），响应开头：" + snippet(responseBody));
        }
    }

    private LlmChatResult parse(String responseBody, AiEffectiveConfig config, long startedAt) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody == null ? "" : responseBody);
        } catch (Exception e) {
            // 供应商返回了非 JSON（HTML 网关错误页 / SSE 流 / 空体等）：带响应开头摘要，
            // 让用户不用抓包就能判断是接入点配错、网关拦截还是协议不兼容。
            throw new BusinessException(502, "LLM 响应解析失败：" + e.getClass().getSimpleName()
                    + "（响应非 JSON，疑似网关错误页/流式响应/协议不兼容），响应开头："
                    + snippet(responseBody));
        }
        JsonNode error = root.path("error");
        if (error.isObject() && !error.path("message").isMissingNode()) {
            throw new BusinessException(502, "LLM 接入点返回错误：" + error.path("message").asText("unknown"));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BusinessException(502, "LLM 响应缺少 choices 字段（OpenAI 兼容协议），响应开头："
                    + snippet(responseBody));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = extractText(message.path("content"));
        if (content == null && !message.path("reasoning_content").isMissingNode()
                && !message.path("reasoning_content").asText().isBlank()) {
            throw new BusinessException(502, "模型仅返回思考内容（reasoning_content），未输出正文——"
                    + "请在模型选择上避开纯推理型模型，或调大 max_tokens 后重试");
        }
        if (content == null) {
            throw new BusinessException(502, "LLM 响应缺少 message.content 字段，响应开头："
                    + snippet(responseBody));
        }
        // W10：截断检测。finish_reason=length 表示输出被 max_tokens 砍断，截断的 JSON 解析必然失败，
        // 若放行会在业务层退化成模糊的「AI 建议不可用」（W10 排查根因）。此处提前给出可诊断原因。
        // 供应商未返回 finish_reason 时不判（避免误报）。
        if ("length".equals(choice.path("finish_reason").asText(""))) {
            throw new BusinessException(502, "LLM 输出被 max_tokens 截断（finish_reason=length）——"
                    + "请调大该能力的 max_tokens 或精简输出要求；已收到内容开头：" + snippet(content));
        }
        JsonNode usage = root.path("usage");
        return new LlmChatResult(
                content,
                root.path("model").asText(config.model()),
                usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null,
                usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null,
                System.currentTimeMillis() - startedAt);
    }

    /**
     * content 字段取值：字符串直接用；部分网关返回数组格式
     * （[{type:"text",text:"..."},...]）时拼接全部 text 段；其他形态视为缺失。
     */
    private String extractText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("type").asText("text").equals("text") && part.hasNonNull("text")) {
                    joined.append(part.path("text").asText());
                }
            }
            return joined.toString();
        }
        return null;
    }

    /** 响应开头摘要（≤200 字符，换行折空格）；仅诊断用，绝不包含请求侧密钥。 */
    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "（空响应体）";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
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
                    + "）：接入点不可达或超时，请检查 AI 设置页的接入点地址与出网白名单");
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
