package com.finance.system.bank;

import com.finance.system.bank.dto.AccountCompanyAssignRequest;
import com.finance.system.bank.dto.AiCompanyApplyRequest;
import com.finance.system.bank.dto.AiCompanyApplyResponse;
import com.finance.system.bank.dto.AiCompanySuggestionResponse;
import com.finance.system.bank.dto.CompanyArchiveAccount;
import com.finance.system.bank.dto.CompanyArchiveCompany;
import com.finance.system.bank.dto.CompanyArchiveNameRequest;
import com.finance.system.bank.dto.CompanyArchiveView;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-archive filing surface for the drag-and-drop drawer inside the
 * bank-account page. Archive edits are bank:manage (ADMIN) because re-filing
 * an account rewrites the historical balance/statement rows' company scope.
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class CompanyArchiveController {

    private final CompanyArchiveService archiveService;
    private final AiCompanyClassifierService aiClassifierService;

    public CompanyArchiveController(CompanyArchiveService archiveService,
                                    AiCompanyClassifierService aiClassifierService) {
        this.archiveService = archiveService;
        this.aiClassifierService = aiClassifierService;
    }

    @GetMapping("/bank-account-archive")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "View all company archives and every bank account across them")
    public ApiResponse<CompanyArchiveView> view(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(archiveService.view());
    }

    @PostMapping("/bank-account-archive/companies")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Create a company archive")
    public ApiResponse<CompanyArchiveCompany> createCompany(@Valid @RequestBody CompanyArchiveNameRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("公司档案已创建", archiveService.createCompany(request.name()));
    }

    @PutMapping("/bank-account-archive/companies/{id}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Rename a company archive")
    public ApiResponse<CompanyArchiveCompany> renameCompany(@PathVariable Long id,
                                                            @Valid @RequestBody CompanyArchiveNameRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("公司档案已更新", archiveService.renameCompany(id, request.name()));
    }

    @PutMapping("/bank-account-archive/accounts/{id}/company")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Re-file a bank account into a company archive (history follows)")
    public ApiResponse<CompanyArchiveAccount> assignAccount(@PathVariable Long id,
                                                            @Valid @RequestBody AccountCompanyAssignRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("账户归类完成", archiveService.assignAccount(id, request.companyId()));
    }

    /**
     * AI 归类建议（V32）：推断「账户名 → 公司主体」映射供预览确认，AI 只建议不执行。
     * 需要 ai:use（能力端点统一口径）+ bank:manage（归档域）。
     */
    @PostMapping("/bank-account-archive/ai-suggest-companies")
    @PreAuthorize("hasAuthority('ai:use') and hasAuthority('bank:manage')")
    @Operation(summary = "AI-suggest company archive mappings for unfiled accounts")
    public ApiResponse<AiCompanySuggestionResponse> suggestCompanies(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("AI 归类建议已生成", aiClassifierService.suggest(principal.getId()));
    }

    /** AI 归类建议批量应用：仅应用用户勾选的行，公司不存在则建档，历史流水归属一并迁移。 */
    @PostMapping("/bank-account-archive/ai-apply-companies")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Apply confirmed AI filing suggestions (create company when missing)")
    public ApiResponse<AiCompanyApplyResponse> applySuggestions(
            @RequestBody AiCompanyApplyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("归类应用完成", archiveService.applySuggestions(request));
    }
}
