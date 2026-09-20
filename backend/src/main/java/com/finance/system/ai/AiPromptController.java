package com.finance.system.ai;

import com.finance.system.ai.dto.AiPromptView;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 提示词配置端点（V38，W9 需求 4）：
 *
 * <ul>
 *   <li>{@code GET /api/ai/prompts} —— 全目录视图（当前生效值 + 默认值 + 是否自定义），</li>
 *   <li>{@code PUT /api/ai/prompts/{capability}} —— 保存覆盖（即时生效），</li>
 *   <li>{@code DELETE /api/ai/prompts/{capability}} —— 重置（删覆盖行回落默认；无覆盖 404）。</li>
 * </ul>
 *
 * <p>提示词全局生效、影响所有 AI 结果，属系统配置——三个端点一律 {@code ai:config}（仅超管）。</p>
 */
@RestController
@RequestMapping("/api/ai/prompts")
public class AiPromptController {

    /** 保存提示词的请求体（JSON 转义规则同提示词正文，服务端 strip + 长度校验）。 */
    public record AiPromptUpsertRequest(@NotBlank(message = "Prompt is required") String systemPrompt) {
    }

    private final AiPromptService promptService;
    private final SystemAuditService auditService;

    public AiPromptController(AiPromptService promptService, SystemAuditService auditService) {
        this.promptService = promptService;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "List configurable AI prompt capabilities (effective + default)")
    public ApiResponse<List<AiPromptView>> list() {
        return ApiResponse.success(promptService.list());
    }

    @PutMapping("/{capability}")
    @PreAuthorize("hasAuthority('ai:config')")
    @Operation(summary = "Override the system prompt of one AI capability (takes effect immediately)")
    public ApiResponse<AiPromptView> save(@PathVariable String capability,
                                          @org.springframework.validation.annotation.Validated
                                          @RequestBody AiPromptUpsertRequest request,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        AiPromptView view = promptService.upsert(capability, request.systemPrompt(), principal.getId());
        auditService.record(principal.getId(), "AI_PROMPT_SAVE", "AI_PROMPT", capability,
                null, "SUCCESS", "length=" + request.systemPrompt().strip().length());
        return ApiResponse.success("提示词已保存并即时生效", view);
    }

    @DeleteMapping("/{capability}")
    @PreAuthorize("hasAuthority('ai:config')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Reset one AI capability's prompt back to the built-in default")
    public ApiResponse<Void> reset(@PathVariable String capability,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        promptService.reset(capability, principal.getId());
        auditService.record(principal.getId(), "AI_PROMPT_RESET", "AI_PROMPT", capability,
                null, "SUCCESS", null);
        return ApiResponse.success("已恢复系统默认提示词", null);
    }
}
