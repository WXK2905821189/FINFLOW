package com.finance.system.ai;

import com.finance.system.ai.dto.AiAccountingSuggestionRequest;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.ai.dto.AiCallLogResponse;
import com.finance.system.ai.dto.AiConfigResponse;
import com.finance.system.ai.dto.AiConfigTestRequest;
import com.finance.system.ai.dto.AiConfigUpsertRequest;
import com.finance.system.ai.dto.AiSelfTestResponse;
import com.finance.system.ai.dto.AiStatusResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 能力地基端点（V27 地基 + V28 在线配置 + A1 智能入账建议）：
 *
 * <ul>
 *   <li>{@code GET /api/ai/status} —— 网关与能力开关状态（密钥只暴露"是否已配置"），
 *       权限 {@code ai:use}；</li>
 *   <li>{@code GET/PUT /api/ai/config} —— AI 设置页读写（DB 在线配置覆盖 env，
 *       密钥只存密文、响应只有 hint 尾 4 位），权限 {@code ai:config}；</li>
 *   <li>{@code POST /api/ai/config/test} —— 用表单当前值（可未保存）做连通性测试，
 *       权限 {@code ai:config}；</li>
 *   <li>{@code POST /api/ai/self-test} —— 生效配置连通性自检（真实打一次 LLM 往返并审计），
 *       权限 {@code ai:config}；</li>
 *   <li>{@code POST /api/ai/accounting-suggestion} —— A1 智能入账建议（AI 只建议不执行），
 *       权限 {@code ai:use} + capability 开关；</li>
 *   <li>{@code GET /api/ai/call-logs} —— 调用审计查看（哈希+摘要口径），权限 {@code ai:config}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiGatewayService gatewayService;
    private final AiCallLogService callLogService;
    private final AiConfigService configService;
    private final AccountingSuggestionService accountingSuggestionService;
    private final LlmGateway llmGateway;

    public AiController(AiGatewayService gatewayService, AiCallLogService callLogService,
                        AiConfigService configService, AccountingSuggestionService accountingSuggestionService,
                        LlmGateway llmGateway) {
        this.gatewayService = gatewayService;
        this.callLogService = callLogService;
        this.configService = configService;
        this.accountingSuggestionService = accountingSuggestionService;
        this.llmGateway = llmGateway;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ai:use')")
    @Operation(summary = "AI gateway status (no secrets — only whether the key is configured)")
    public ApiResponse<AiStatusResponse> status() {
        return ApiResponse.success(gatewayService.status());
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "AI settings view (DB row masked + effective merged config)")
    public ApiResponse<AiConfigResponse> config() {
        return ApiResponse.success(configService.view());
    }

    @PutMapping("/config")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Save AI settings (DB overrides env; apiKey omitted = keep, empty = clear)")
    public ApiResponse<AiConfigResponse> updateConfig(@Valid @RequestBody AiConfigUpsertRequest request,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("AI 配置已保存并即时生效", configService.upsert(request, principal.getId()));
    }

    @PostMapping("/config/test")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Connectivity test with pending form values (audited under self-test)")
    public ApiResponse<AiSelfTestResponse> testConfig(@Valid @RequestBody AiConfigTestRequest request,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        AiEffectiveConfig base = configService.effective(
                request.baseUrl(), request.apiKey(), request.model());
        if (!base.apiKeyConfigured()) {
            throw new BusinessException(400, "请先填写 API 密钥（表单或已保存配置）");
        }
        LlmChatRequest chat = new LlmChatRequest(AiGatewayService.SELF_TEST,
                "你是 FINFLOW 财务系统的连通性探针。无论收到什么，只回复一个词：PONG",
                "ping", 0.0, 16);
        return ApiResponse.success("连接成功", toSelfTestResponse(
                gatewayService.auditedChat(AiGatewayService.SELF_TEST, principal.getId(), base, chat)));
    }

    @PostMapping("/config/models")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Fetch available models from the provider (OpenAI-compatible GET /models, pending form values)")
    public ApiResponse<List<String>> models(@Valid @RequestBody AiConfigTestRequest request) {
        AiEffectiveConfig config = configService.effective(request.baseUrl(), request.apiKey(), null);
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new BusinessException(400, "请先填写接入点 Base URL");
        }
        if (!config.apiKeyConfigured()) {
            throw new BusinessException(400, "请先填写 API 密钥（表单或已保存配置）");
        }
        return ApiResponse.success("模型列表已获取", llmGateway.listModels(config));
    }

    @PostMapping("/self-test")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Run a connectivity self-test against the effective config (audited)")
    public ApiResponse<AiSelfTestResponse> selfTest(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("自检完成", gatewayService.selfTest(principal.getId()));
    }

    @PostMapping("/accounting-suggestion")
    @PreAuthorize("hasAuthority('ai:use')")
    @Operation(summary = "A1: AI pre-fill suggestion for a statement row (suggest only, never executes)")
    public ApiResponse<AiAccountingSuggestionResponse> accountingSuggestion(
            @Valid @RequestBody AiAccountingSuggestionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("AI 建议仅供参考，结论以人工复核为准",
                accountingSuggestionService.suggest(request.statementId(), principal.getId()));
    }

    @GetMapping("/call-logs")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Recent AI call audit logs (hash + masked summary, no full context)")
    public ApiResponse<List<AiCallLogResponse>> callLogs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(callLogService.recent(limit).stream()
                .map(AiCallLogResponse::from).toList());
    }

    private static AiSelfTestResponse toSelfTestResponse(LlmChatResult result) {
        return new AiSelfTestResponse(result.content(), result.model(), result.durationMillis(),
                result.promptTokens(), result.completionTokens());
    }
}
