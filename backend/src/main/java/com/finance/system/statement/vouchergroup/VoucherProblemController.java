package com.finance.system.statement.vouchergroup;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

/**
 * A2 问题凭证编辑器端点（W16-A2）。
 *
 * <p>与凭证中心 {@code VoucherGroupController} 同包同权限（{@code voucher:push}）；
 * 问题桶语义：{@code statement_record.problem_type IS NOT NULL}（V44）。
 * 独立 Controller——凭证中心契约继续独立演进，不与 statement-import 面混装。</p>
 */
@RestController
@RequestMapping("/api/vouchers/problems")
@SecurityRequirement(name = "bearerAuth")
public class VoucherProblemController {

    private final VoucherProblemService problemService;

    public VoucherProblemController(VoucherProblemService problemService) {
        this.problemService = problemService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "问题凭证列表（分桶 type=CANDIDATES/UNMATCHED/MANUAL_AMOUNT/ELIGIBLE/PUSH_FAILED，"
            + "keyword 匹配流水号/摘要/对手方；problem_updated_at 倒序）")
    public ApiResponse<PageResponse<VoucherProblemService.ProblemRowResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(problemService.pageProblems(
                page, size, type, keyword, principal.getId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "问题凭证详情：流水上下文 + 落桶原因 + 已保存编辑态 + 规则预填分录")
    public ApiResponse<VoucherProblemService.ProblemDetailResponse> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(problemService.getProblem(id, principal.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "保存编辑并重校验（借贷平衡/科目必明细/金额正数；通过后写 problem_edit_json，审计 PROBLEM_EDIT）")
    public ApiResponse<VoucherProblemService.ProblemRowResponse> save(
            @PathVariable Long id,
            @RequestBody VoucherProblemService.VoucherProblemEditRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("问题凭证编辑已保存",
                problemService.saveEdit(id, request, principal.getId()));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('voucher:push')")
    @Operation(summary = "修复完成提交推送：以编辑态（无则规则预填）走 pushManual——"
            + "成功 GL_PUSHED 自动出列；失败留桶并刷新 problem_reason")
    public ApiResponse<VoucherProblemService.SubmitResult> submit(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("问题凭证已提交推送",
                problemService.submit(id, principal.getId()));
    }
}
