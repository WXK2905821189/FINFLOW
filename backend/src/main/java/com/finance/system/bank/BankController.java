package com.finance.system.bank;

import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
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

    public BankController(BankAccountService bankAccountService) {
        this.bankAccountService = bankAccountService;
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
}
