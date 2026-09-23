package com.finance.system.bankdata;

import com.finance.system.bankdata.dto.BankDataBackfillRequest;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * W17 包 F：历史数据回补受理端点（内部运维用）。受理后后台逐账户串行推进，
 * 片间限速；进度通过同步任务列表的 BACKFILL 记录观测。
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Bank data pipeline", description = "Canonical simulated bank-data synchronization and projection API")
public class BankDataBackfillController {

    private final BankDataBackfillService backfillService;

    public BankDataBackfillController(BankDataBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/bank-data/backfill")
    @PreAuthorize("hasAuthority('bankdata:sync:trigger')")
    @Operation(summary = "Trigger a historical data backfill (accepted async)",
            description = "Splits the requested history range into chunks and replays the regular sync "
                    + "pipeline per chunk. Returns immediately with the run task id; progress is visible "
                    + "in the sync task list as BACKFILL records.")
    public ApiResponse<Map<String, Object>> backfill(@Valid @RequestBody BankDataBackfillRequest request,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        Long runTaskId = backfillService.submitAsync(principal.getId(),
                new BankDataBackfillService.BackfillRequest(request.accountId(), request.historyStart(),
                        request.historyEnd(), request.wantStatements(), request.wantBalances()));
        return ApiResponse.success("Bank data backfill accepted", Map.of(
                "runTaskId", runTaskId,
                "message", "Backfill accepted and running in the background; check bank_data_sync_task "
                        + "trigger_type=BACKFILL for progress"));
    }
}
