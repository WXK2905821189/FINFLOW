package com.finance.system.bankdata;

import com.finance.system.bankdata.dto.BankDataRawMessageDetailResponse;
import com.finance.system.bankdata.dto.BankDataRawMessageResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Read-only access to the captured bank responses.
 *
 * <p>Guarded by {@code bankdata:raw:view} rather than the broader {@code bankdata:view}:
 * the detail endpoint is the only place in the pipeline that returns a raw response
 * body, so access to it is granted explicitly instead of inherited.
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Bank raw messages", description = "Captured bank response payloads as connectivity evidence")
public class BankRawMessageController {

    private final RawMessageQueryService service;

    public BankRawMessageController(RawMessageQueryService service) {
        this.service = service;
    }

    @GetMapping("/bank-data-raw-messages")
    @PreAuthorize("hasAuthority('bankdata:raw:view')")
    @Operation(summary = "List captured bank responses (metadata only, never the payload)")
    public ApiResponse<PageResponse<BankDataRawMessageResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String adapterCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.list(principal.getId(), page, size, accountId, taskNo,
                adapterCode, from, to));
    }

    @GetMapping("/bank-data-raw-messages/{id}")
    @PreAuthorize("hasAuthority('bankdata:raw:view')")
    @Operation(summary = "Open one captured bank response, including its payload")
    public ApiResponse<BankDataRawMessageDetailResponse> detail(@PathVariable Long id,
                                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.detail(principal.getId(), id));
    }
}
