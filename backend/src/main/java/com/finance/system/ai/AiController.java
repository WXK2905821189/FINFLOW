package com.finance.system.ai;

import com.finance.system.ai.dto.AiCallLogResponse;
import com.finance.system.ai.dto.AiSelfTestResponse;
import com.finance.system.ai.dto.AiStatusResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 能力地基端点（V27）：
 *
 * <ul>
 *   <li>{@code GET /api/ai/status} —— 网关与能力开关状态（密钥只暴露"是否已配置"），
 *       权限 {@code ai:use}；</li>
 *   <li>{@code POST /api/ai/self-test} —— 连通性自检（真实打一次 LLM 往返并审计），
 *       权限 {@code ai:config}；</li>
 *   <li>{@code GET /api/ai/call-logs} —— 调用审计查看（哈希+摘要口径），权限 {@code ai:config}。</li>
 * </ul>
 *
 * <p>能力调用端点（A1 入账建议等）按能力一个路径陆续挂在各业务页，
 * 均要求 {@code ai:use} + 该能力开关开启（fail-closed，默认 403）。</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiGatewayService gatewayService;
    private final AiCallLogService callLogService;

    public AiController(AiGatewayService gatewayService, AiCallLogService callLogService) {
        this.gatewayService = gatewayService;
        this.callLogService = callLogService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ai:use')")
    @Operation(summary = "AI gateway status (no secrets — only whether the key is configured)")
    public ApiResponse<AiStatusResponse> status() {
        return ApiResponse.success(gatewayService.status());
    }

    @PostMapping("/self-test")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Run a connectivity self-test against the configured LLM provider (audited)")
    public ApiResponse<AiSelfTestResponse> selfTest(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("自检完成", gatewayService.selfTest(principal.getId()));
    }

    @GetMapping("/call-logs")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Recent AI call audit logs (hash + masked summary, no full context)")
    public ApiResponse<List<AiCallLogResponse>> callLogs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(callLogService.recent(limit).stream()
                .map(AiCallLogResponse::from).toList());
    }
}
