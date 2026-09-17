package com.finance.system.statement;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.dto.AiVoucherBatchRequest;
import com.finance.system.statement.dto.AiVoucherBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一键 AI 制证（2026-09-16）：银行流水 → AI 建议 → 推送金蝶（save→submit，audit 留给金蝶侧
 * 人工审核）。独立于 {@code StatementController}，避免与并行改动交叉；权限取终局闸门
 * {@code voucher:push}（复核闸门已内化进服务端流程并留审计）。
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankDataAccountingController {

    private final BankDataAccountingService accountingService;

    public BankDataAccountingController(BankDataAccountingService accountingService) {
        this.accountingService = accountingService;
    }

    @PostMapping("/bank-data/statements/ai-voucher")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "One-click AI voucher creation for real bank statements "
            + "(transfer → AI suggestion → DRAFT pending review or PUSH to Kingdee)")
    public ApiResponse<AiVoucherBatchResponse> createAiVouchers(@Valid @RequestBody AiVoucherBatchRequest request,
                                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("AI 制证处理完成",
                accountingService.createVouchers(request.statementIds(), principal.getId(), request.mode()));
    }
}
