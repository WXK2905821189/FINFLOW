package com.finance.system.bank;

import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.bank.dto.BankConnectionTestResponse;
import com.finance.system.bankdata.aggregation.BankConnectionTestService;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bank account management. The legacy /banks and /transfers surfaces were
 * removed: active payments/transfers are out of the v0.4 product scope
 * (PRD) and the endpoints had no client.
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankController {

    private final BankAccountService bankAccountService;
    private final BankConnectionTestService connectionTestService;

    public BankController(BankAccountService bankAccountService,
                          BankConnectionTestService connectionTestService) {
        this.bankAccountService = bankAccountService;
        this.connectionTestService = connectionTestService;
    }

    @GetMapping("/bank-accounts")
    @PreAuthorize("hasAuthority('bank:view')")
    @Operation(summary = "List masked bank accounts")
    public ApiResponse<List<BankAccountResponse>> accounts(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(bankAccountService.listResponses(principal.getId()));
    }

    @PostMapping("/bank-accounts")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Create a bank account")
    public ApiResponse<BankAccountResponse> createAccount(@Valid @RequestBody BankAccountRequest request,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Bank account created", bankAccountService.create(principal.getId(), request));
    }

    @PutMapping("/bank-accounts/{id}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Update a bank account")
    public ApiResponse<BankAccountResponse> updateAccount(@PathVariable Long id, @Valid @RequestBody BankAccountRequest request,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Bank account updated", bankAccountService.updateAccount(principal.getId(), id, request));
    }

    /**
     * Read-only connectivity probe behind the archive page "测试连接" button. The mapping
     * was missing from the original feature commit (only the service injection landed),
     * so the button 404'd and surfaced as an internal server error.
     */
    @PostMapping("/bank-accounts/{id}/test-connection")
    @PreAuthorize("hasAuthority('bank:manage') or hasAuthority('bankdata:sync:trigger')")
    @Operation(summary = "Probe real bank connectivity for one account (read-only)")
    public ApiResponse<BankConnectionTestResponse> testConnection(@PathVariable Long id,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("连接测试完成", connectionTestService.test(principal.getId(), id));
    }

    /**
     * Archive removal (V32 soft delete): statements/balances/raw messages are retained,
     * the account disappears from the archive board, dropdowns, queries and the scheduler.
     */
    @DeleteMapping("/bank-accounts/{id}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Soft-delete a bank account (history retained)")
    public ApiResponse<Void> deleteAccount(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        bankAccountService.deleteAccount(principal.getId(), id);
        return ApiResponse.success("Bank account removed from archive", null);
    }
}
