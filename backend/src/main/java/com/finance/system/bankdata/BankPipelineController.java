package com.finance.system.bankdata;

import com.finance.system.bankdata.dto.BankDataProjectionResponse;
import com.finance.system.bankdata.dto.BankSyncJobDetailResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.bankdata.dto.BankSyncJobTriggerRequest;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankPipelineController {

    private final BankDataSyncService service;

    public BankPipelineController(BankDataSyncService service) {
        this.service = service;
    }

    @PostMapping("/bank-sync-jobs")
    @PreAuthorize("hasAnyAuthority('bankdata:sync', 'bank-sync:trigger')")
    @Operation(summary = "Create a controlled bank synchronization job")
    public ApiResponse<BankSyncJobResponse> trigger(
            @Valid @RequestBody BankSyncJobTriggerRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Bank synchronization job created",
                service.triggerJob(principal.getId(), request, requestId).job());
    }

    @GetMapping("/bank-sync-jobs")
    @PreAuthorize("hasAnyAuthority('bankdata:view', 'operation:monitor')")
    @Operation(summary = "List controlled bank synchronization jobs")
    public ApiResponse<PageResponse<BankSyncJobResponse>> jobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobType,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.listJobs(principal.getId(), page, size, status, jobType));
    }

    @GetMapping("/bank-sync-jobs/{id}")
    @PreAuthorize("hasAnyAuthority('bankdata:view', 'operation:monitor')")
    @Operation(summary = "Get a controlled bank synchronization job")
    public ApiResponse<BankSyncJobDetailResponse> job(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.getJob(principal.getId(), id));
    }

    @GetMapping("/bank-data/{resource}")
    @PreAuthorize("hasAnyAuthority('bankdata:view', 'bankdata:balance:view', 'bankdata:statement:view', 'bankdata:receipt:view', 'bankdata:reconciliation:view', 'bankdata:payment:view', 'bankdata:payroll:view')")
    @Operation(summary = "Query a controlled bank data projection")
    public ApiResponse<PageResponse<BankDataProjectionResponse>> projection(
            @PathVariable String resource,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("bankdata:view"))
                && !principal.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals(permissionFor(resource)))) {
            throw new org.springframework.security.access.AccessDeniedException("Bank data projection permission is required");
        }
        return ApiResponse.success(service.queryProjection(principal.getId(), resource, page, size, status,
                accountId, keyword, from, to));
    }

    private String permissionFor(String resource) {
        return Map.of(
                "balances", "bankdata:balance:view",
                "statements", "bankdata:statement:view",
                "receipts", "bankdata:receipt:view",
                "reconciliations", "bankdata:reconciliation:view",
                "payments", "bankdata:payment:view",
                "payroll", "bankdata:payroll:view"
        ).getOrDefault(resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT), "bankdata:invalid");
    }
}
