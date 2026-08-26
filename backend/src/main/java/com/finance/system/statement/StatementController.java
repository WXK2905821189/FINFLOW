package com.finance.system.statement;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.dto.StatementDashboardResponse;
import com.finance.system.statement.dto.StatementDetailResponse;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementImportRequest;
import com.finance.system.statement.dto.StatementResponse;
import com.finance.system.statement.dto.StatementReviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @PostMapping("/statement-imports")
    @PreAuthorize("hasAuthority('statement:import')")
    @Operation(summary = "Import a file or simulated bank statement batch")
    public ApiResponse<StatementImportBatchResponse> importBatch(@Valid @RequestBody StatementImportRequest request,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Statement import completed", statementService.importBatch(request, principal.getId()));
    }

    @GetMapping("/statement-imports")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "List statement import batches")
    public ApiResponse<PageResponse<StatementImportBatchResponse>> batches(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(statementService.pageBatches(page, size));
    }

    @GetMapping("/statement-imports/{id}")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Get a statement import batch")
    public ApiResponse<StatementImportBatchResponse> batch(@PathVariable Long id) {
        return ApiResponse.success(statementService.getBatch(id));
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "List imported bank statements")
    public ApiResponse<PageResponse<StatementResponse>> statements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String validationStatus,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String pushStatus) {
        return ApiResponse.success(statementService.pageStatements(page, size, validationStatus, reviewStatus, pushStatus));
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize("hasAuthority('statement:view')")
    @Operation(summary = "Get a statement with audit trail")
    public ApiResponse<StatementDetailResponse> statement(@PathVariable Long id) {
        return ApiResponse.success(statementService.getDetail(id));
    }

    @PostMapping("/statements/{id}/review")
    @PreAuthorize("hasAuthority('statement:review')")
    @Operation(summary = "Approve or reject a validated statement")
    public ApiResponse<StatementResponse> review(@PathVariable Long id,
                                                  @Valid @RequestBody StatementReviewRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Statement review completed", statementService.review(id, request, principal.getId()));
    }

    @PostMapping("/statements/{id}/voucher-push")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Push an approved statement to the Kingdee mock gateway")
    public ApiResponse<StatementResponse> pushVoucher(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Voucher push completed", statementService.pushVoucher(id, principal.getId()));
    }

    @GetMapping("/reconciliation/dashboard")
    @PreAuthorize("hasAuthority('reconciliation:view')")
    @Operation(summary = "Get statement reconciliation dashboard totals")
    public ApiResponse<StatementDashboardResponse> dashboard() {
        return ApiResponse.success(statementService.dashboard());
    }
}
