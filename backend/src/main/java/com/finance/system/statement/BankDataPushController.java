package com.finance.system.statement;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import com.finance.system.statement.dto.PushBatchRequest;
import com.finance.system.statement.dto.PushJobResponse;
import com.finance.system.statement.dto.PushSubmitResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 一键推送至金蝶（W16-A1，2026-09-22 用户拍板）：银行流水 → 规则中心匹配 →
 * 仅唯一命中自动推送金蝶草稿，其余落问题凭证。异步任务：提交即返回，
 * 进度与逐行结果在「凭证中心」轮询。权限取终局闸门 {@code voucher:push}。
 *
 * <p>取代既有 {@code /bank-data/statements/ai-voucher} 三端点（AI 制证全链路退役）。</p>
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankDataPushController {

    private final BankPushJobService jobService;

    public BankDataPushController(BankPushJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/bank-data/statements/push-to-kingdee")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "One-click push of selected bank statements to Kingdee via the rule engine "
            + "(background job: unique-rule matches auto-push, the rest become problem vouchers)")
    public ApiResponse<PushSubmitResponse> pushToKingdee(
            @Valid @RequestBody PushBatchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        PushJobResponse job = jobService.submit(request.statementIds(), principal.getId());
        return ApiResponse.success("一键推送任务已提交", PushSubmitResponse.of(job.id(), job.totalCount()));
    }

    @GetMapping("/bank-data/push-jobs/latest")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "Latest one-click push background job of the current company "
            + "(progress + per-row results for the voucher center)")
    public ApiResponse<PushJobResponse> latestJob(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("ok", jobService.latest(principal.getId()));
    }

    @GetMapping("/bank-data/push-jobs/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "One-click push background job by id (cross-company requires bankdata:cross-company:view)")
    public ApiResponse<PushJobResponse> jobById(@PathVariable Long id,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("ok", jobService.byId(id, principal.getId()));
    }
}
