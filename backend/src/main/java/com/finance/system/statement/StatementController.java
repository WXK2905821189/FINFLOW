package com.finance.system.statement;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.dto.StatementBatchOpRequest;
import com.finance.system.statement.dto.StatementBatchOpResponse;
import com.finance.system.statement.dto.StatementBatchPushRequest;
import com.finance.system.statement.dto.StatementDashboardResponse;
import com.finance.system.statement.dto.StatementDetailResponse;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementImportRequest;
import com.finance.system.statement.dto.StatementResponse;
import com.finance.system.statement.dto.StatementReviewRequest;
import com.finance.system.statement.dto.StatementTransferRequest;
import com.finance.system.statement.dto.VoucherDraftSaveRequest;
import com.finance.system.statement.dto.VoucherSuggestionDto;
import com.finance.system.statement.kingdee.KingdeeConnectionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class StatementController {

    private final StatementService statementService;
    private final BankDataAccountingService bankDataAccountingService;

    public StatementController(StatementService statementService,
                               BankDataAccountingService bankDataAccountingService) {
        this.statementService = statementService;
        this.bankDataAccountingService = bankDataAccountingService;
    }

    @PostMapping("/statement-imports")
    @PreAuthorize("hasAuthority('statement:import')")
    @Operation(summary = "Import a file or simulated bank statement batch")
    public ApiResponse<StatementImportBatchResponse> importBatch(@Valid @RequestBody StatementImportRequest request,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Statement import completed", statementService.importBatch(request, principal.getId()));
    }

    @PostMapping("/statements/transfer-from-bankdata")
    @PreAuthorize("hasAuthority('statement:import')")
    @Operation(summary = "Transfer selected bank-data statement rows into the standard statement ledger")
    public ApiResponse<StatementImportBatchResponse> transferFromBankData(
            @Valid @RequestBody StatementTransferRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("银行流水转入完成", statementService.transferFromBankData(request, principal.getId()));
    }

    @GetMapping("/statement-imports")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "List statement import batches")
    public ApiResponse<PageResponse<StatementImportBatchResponse>> batches(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.pageBatches(page, size, principal.getId()));
    }

    @GetMapping("/statement-imports/{id}")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Get a statement import batch")
    public ApiResponse<StatementImportBatchResponse> batch(@PathVariable Long id,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.getBatch(id, principal.getId()));
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "List imported bank statements")
    public ApiResponse<PageResponse<StatementResponse>> statements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String validationStatus,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String pushStatus,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.pageStatements(page, size, validationStatus, reviewStatus, pushStatus,
                principal.getId()));
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Get a statement with audit trail")
    public ApiResponse<StatementDetailResponse> statement(@PathVariable Long id,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.getDetail(id, principal.getId()));
    }

    @PostMapping("/statements/{id}/review")
    @PreAuthorize("hasAuthority('statement:review')")
    @Operation(summary = "Approve or reject a validated statement")
    public ApiResponse<StatementResponse> review(@PathVariable Long id,
                                                  @Valid @RequestBody StatementReviewRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Statement review completed", statementService.review(id, request, principal.getId()));
    }

    @PostMapping("/statements/batch-review")
    @PreAuthorize("hasAuthority('statement:review')")
    @Operation(summary = "Batch approve or reject statement drafts (voucher draft workbench)")
    public ApiResponse<StatementBatchOpResponse> batchReview(@Valid @RequestBody StatementBatchOpRequest request,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Batch review completed",
                statementService.batchReview(request, principal.getId()));
    }

    @PostMapping("/statements/batch-push")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Batch push approved statements to the Kingdee gateway (voucher draft workbench)")
    public ApiResponse<StatementBatchOpResponse> batchPush(@Valid @RequestBody StatementBatchPushRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Batch push completed",
                statementService.batchPush(request, principal.getId()));
    }

    @PostMapping("/statements/{id}/ai-suggestion")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Regenerate the AI accounting suggestion for a pending draft "
            + "(writes the review comment only; AI guard ai:use applies inside)")
    public ApiResponse<AiAccountingSuggestionResponse> refreshAiSuggestion(@PathVariable Long id,
                                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("AI 建议已更新",
                bankDataAccountingService.refreshAiSuggestion(id, principal.getId()));
    }

    @PutMapping("/statements/{id}/voucher-draft")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Save human-corrected voucher entries for a draft (V33 凭证草稿详情："
            + "金蝶式单据页 AI 预填 → 人工复核+改；主摘要同步回写 statement.summary 供金蝶单据备注)")
    public ApiResponse<VoucherSuggestionDto> saveVoucherDraft(@PathVariable Long id,
                                                               @RequestBody VoucherDraftSaveRequest request,
                                                               @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("凭证草稿已保存",
                bankDataAccountingService.saveVoucherDraft(id, request, principal.getId()));
    }

    @GetMapping("/statements/kingdee/ping")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Read-only Kingdee connectivity probe (never creates or modifies Kingdee data)")
    public ApiResponse<KingdeeConnectionStatus> pingKingdee(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.pingKingdee());
    }

    @PostMapping("/statements/{id}/voucher-push")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Push an approved statement to the Kingdee mock gateway")
    public ApiResponse<StatementResponse> pushVoucher(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Voucher push completed", statementService.pushVoucher(id, principal.getId()));
    }

    /** W8（2026-09-20）：重新打开已驳回的流水（REJECTED→PENDING），使其可重新制证；审计 REOPEN。 */
    @PostMapping("/statements/{id}/reopen")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Reopen a rejected statement so it can be re-vouchered (audit: REOPEN)")
    public ApiResponse<StatementResponse> reopen(@PathVariable Long id,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("流水已重新打开，可重新制证", statementService.reopen(id, principal.getId()));
    }

    @GetMapping("/reconciliation/dashboard")
    @PreAuthorize("hasAuthority('reconciliation:view')")
    @Operation(summary = "Get statement reconciliation dashboard totals")
    public ApiResponse<StatementDashboardResponse> dashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(statementService.dashboard(principal.getId()));
    }
}
