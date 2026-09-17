package com.finance.system.statement.vouchergroup;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Voucher center list (V34 ⑦): read-only voucher-group view over the statement pipeline.
 * Kept separate from {@code StatementController} so the voucher-center contract evolves
 * independently of the statement-import surface. Permission matches the other voucher
 * surfaces ({@code voucher:push}).
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class VoucherGroupController {

    private final VoucherGroupService voucherGroupService;

    public VoucherGroupController(VoucherGroupService voucherGroupService) {
        this.voucherGroupService = voucherGroupService;
    }

    @GetMapping("/statements/voucher-groups")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Voucher center list: statement rows projected as voucher groups "
            + "(buckets: ALL/DRAFT/PENDING/PUSHED/FAILED; keyword matches statementNo/voucherNo/summary/counterparty)")
    public ApiResponse<PageResponse<VoucherGroupResponse>> groups(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(voucherGroupService.pageGroups(page, size, status, keyword, principal.getId()));
    }
}
