package com.finance.system.statement;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.dto.AiVoucherBatchRequest;
import com.finance.system.statement.dto.AiVoucherJobResponse;
import com.finance.system.statement.dto.AiVoucherSubmitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 一键 AI 制证（2026-09-16；2026-09-21 DRAFT 异步化）：银行流水 → AI 建议 → 草稿或推送金蝶。
 * 独立于 {@code StatementController}，避免与并行改动交叉；权限取终局闸门 {@code voucher:push}。
 *
 * <p>模式差异（2026-09-21 用户反馈「点 AI 制证为草稿别卡住操作界面」）：
 * DRAFT 走后台任务、提交即返回，进度与结果在「凭证中心」看；PUSH 保持同步。</p>
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankDataAccountingController {

    private final BankDataAccountingService accountingService;
    private final AiVoucherJobService jobService;

    public BankDataAccountingController(BankDataAccountingService accountingService,
                                        AiVoucherJobService jobService) {
        this.accountingService = accountingService;
        this.jobService = jobService;
    }

    @PostMapping("/bank-data/statements/ai-voucher")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "One-click AI voucher creation for real bank statements "
            + "(DRAFT = background job submitting immediately, PUSH = synchronous push to Kingdee)")
    public ApiResponse<AiVoucherSubmitResponse> createAiVouchers(
            @Valid @RequestBody AiVoucherBatchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String mode = request.mode() == null || request.mode().isBlank()
                ? "PUSH" : request.mode().trim().toUpperCase(Locale.ROOT);
        if ("DRAFT".equals(mode)) {
            AiVoucherJobResponse job = jobService.submit(request.statementIds(), principal.getId());
            return ApiResponse.success("AI 制证草稿任务已提交",
                    AiVoucherSubmitResponse.async(job.id(), job.totalCount()));
        }
        return ApiResponse.success("AI 制证处理完成",
                AiVoucherSubmitResponse.sync(
                        accountingService.createVouchers(request.statementIds(), principal.getId(), mode)));
    }

    @GetMapping("/bank-data/ai-voucher-jobs/latest")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Latest AI voucher background job of the current company "
            + "(progress + per-row results for the voucher center)")
    public ApiResponse<AiVoucherJobResponse> latestJob(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("ok", jobService.latest(principal.getId()));
    }

    @GetMapping("/bank-data/ai-voucher-jobs/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "AI voucher background job by id (cross-company requires bankdata:cross-company:view)")
    public ApiResponse<AiVoucherJobResponse> jobById(@org.springframework.web.bind.annotation.PathVariable Long id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("ok", jobService.byId(id, principal.getId()));
    }
}
