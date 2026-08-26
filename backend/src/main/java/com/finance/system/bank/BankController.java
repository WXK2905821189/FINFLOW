package com.finance.system.bank;

import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.bank.dto.BankTransferRequest;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankController {

    private final BankServiceFactory bankServiceFactory;
    private final BankAccountService bankAccountService;

    public BankController(BankServiceFactory bankServiceFactory, BankAccountService bankAccountService) {
        this.bankServiceFactory = bankServiceFactory;
        this.bankAccountService = bankAccountService;
    }

    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('bank:view')")
    @Operation(summary = "List supported bank adapters")
    public ApiResponse<List<String>> supportedBanks() {
        return ApiResponse.success(bankServiceFactory.supportedBankCodes().stream().toList());
    }

    @GetMapping("/bank-accounts")
    @PreAuthorize("hasAuthority('bank:view')")
    @Operation(summary = "List masked bank accounts")
    public ApiResponse<List<BankAccountResponse>> accounts() {
        return ApiResponse.success(bankAccountService.listResponses());
    }

    @PostMapping("/bank-accounts")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Create a bank account")
    public ApiResponse<BankAccountResponse> createAccount(@Valid @RequestBody BankAccountRequest request) {
        return ApiResponse.success("Bank account created", bankAccountService.create(request));
    }

    @PutMapping("/bank-accounts/{id}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Update a bank account")
    public ApiResponse<BankAccountResponse> updateAccount(@PathVariable Long id, @Valid @RequestBody BankAccountRequest request) {
        return ApiResponse.success("Bank account updated", bankAccountService.updateAccount(id, request));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('transfer:create')")
    @Operation(summary = "Submit a bank transfer to the selected adapter")
    public ApiResponse<BankTransferResponse> transfer(@Valid @RequestBody BankTransferRequest request) {
        return ApiResponse.success("Transfer accepted", bankAccountService.submitTransfer(request));
    }
}
