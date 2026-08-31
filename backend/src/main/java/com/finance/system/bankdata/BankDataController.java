package com.finance.system.bankdata;

import com.finance.system.bankdata.dto.BankDataReconciliationResponse;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataConnectionResponse;
import com.finance.system.bankdata.dto.BankDataStatementDetailResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/bank-data")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Bank data compatibility API", description = "Legacy bank-data paths retained for existing clients")
@Deprecated(since = "0.2", forRemoval = false)
public class BankDataController {

    private final BankDataSyncService service;

    public BankDataController(BankDataSyncService service) {
        this.service = service;
    }

    @PostMapping("/sync-tasks")
    @PreAuthorize("hasAnyAuthority('bankdata:sync', 'bankdata:sync:trigger')")
    @Operation(summary = "Trigger a simulated bank data synchronization",
            description = "Compatibility endpoint; new clients should use /api/bank-sync-jobs")
    public ApiResponse<BankDataSyncTaskDetailResponse> trigger(
            @Valid @RequestBody BankDataSyncRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Bank data synchronization completed", service.trigger(principal.getId(), request, requestId));
    }

    @GetMapping("/connections")
    @PreAuthorize("hasAuthority('bankdata:view')")
    @Operation(summary = "List safe bank data connection metadata")
    public ApiResponse<List<BankDataConnectionResponse>> connections(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.listConnections(principal.getId()));
    }

    @GetMapping("/sync-tasks")
    @PreAuthorize("hasAuthority('bankdata:view')")
    @Operation(summary = "List bank data synchronization tasks")
    public ApiResponse<PageResponse<BankDataSyncTaskResponse>> tasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String adapterCode,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.listTasks(principal.getId(), page, size, status, adapterCode));
    }

    @GetMapping("/sync-tasks/{id}")
    @PreAuthorize("hasAuthority('bankdata:view')")
    @Operation(summary = "Get a bank data synchronization task and logs")
    public ApiResponse<BankDataSyncTaskDetailResponse> task(@PathVariable Long id,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.getTaskDetail(principal.getId(), id));
    }

    @GetMapping("/statement-records")
    @PreAuthorize("hasAuthority('bankdata:view')")
    @Operation(summary = "Query detailed normalized bank statement records")
    public ApiResponse<PageResponse<BankDataStatementResponse>> statements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.listStatements(principal.getId(), page, size, bankAccountId, direction, from, to));
    }

    @GetMapping("/statement-records/{id}")
    @PreAuthorize("hasAuthority('bankdata:view')")
    @Operation(summary = "Get a detailed normalized bank statement record with provenance")
    public ApiResponse<BankDataStatementDetailResponse> statement(@PathVariable Long id,
                                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.getStatement(principal.getId(), id));
    }

    @GetMapping("/balance-snapshots")
    @PreAuthorize("hasAnyAuthority('bankdata:view', 'bankdata:balance:view')")
    @Operation(summary = "Query detailed normalized bank balance snapshots")
    public ApiResponse<PageResponse<BankDataBalanceResponse>> balances(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.listBalances(principal.getId(), page, size, bankAccountId, from, to));
    }

    @GetMapping("/reconciliation/summary")
    @PreAuthorize("hasAuthority('bankdata:reconciliation:view')")
    @Operation(summary = "Get bank data reconciliation summary")
    public ApiResponse<BankDataReconciliationResponse> reconciliation(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.reconciliation(principal.getId()));
    }

}
