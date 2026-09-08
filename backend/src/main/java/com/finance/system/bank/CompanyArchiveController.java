package com.finance.system.bank;

import com.finance.system.bank.dto.AccountCompanyAssignRequest;
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

    public CompanyArchiveController(CompanyArchiveService archiveService) {
        this.archiveService = archiveService;
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
}
