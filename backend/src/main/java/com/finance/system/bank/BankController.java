package com.finance.system.bank;

import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.bank.dto.BankTransferRequest;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.bank.dto.PaymentResolutionRequest;
import com.finance.system.bank.dto.PaymentTransferAuditResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class BankController {

    private final BankServiceFactory bankServiceFactory;
    private final BankAccountService bankAccountService;
    private final PaymentTransferService paymentTransferService;

    @Value("${app.product.active-payment-enabled:false}")
    private boolean activePaymentEnabled;

    public BankController(BankServiceFactory bankServiceFactory, BankAccountService bankAccountService,
                          PaymentTransferService paymentTransferService) {
        this.bankServiceFactory = bankServiceFactory;
        this.bankAccountService = bankAccountService;
        this.paymentTransferService = paymentTransferService;
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

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('transfer:create')")
    @Operation(summary = "Create an idempotent transfer application")
    public ApiResponse<BankTransferResponse> transfer(@Valid @RequestBody BankTransferRequest request,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success("Transfer application created",
                paymentTransferService.create(principal.getId(), request, idempotencyKey, requestId));
    }

    @PostMapping("/transfers/{id}/approve")
    @PreAuthorize("hasAuthority('transfer:approve')")
    @Operation(summary = "Approve a transfer created by another user")
    public ApiResponse<BankTransferResponse> approveTransfer(@PathVariable Long id,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success("Transfer approved",
                paymentTransferService.approve(principal.getId(), id, requestId));
    }

    @PostMapping("/transfers/{id}/execute")
    @PreAuthorize("hasAuthority('transfer:execute')")
    @Operation(summary = "Execute an approved transfer exactly once")
    public ApiResponse<BankTransferResponse> executeTransfer(@PathVariable Long id,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success("Transfer execution completed",
                paymentTransferService.execute(principal.getId(), id, requestId));
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAuthority('transaction:view')")
    @Operation(summary = "List transfer applications in the current company")
    public ApiResponse<List<BankTransferResponse>> transfers(@RequestParam(required = false) String status,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success(paymentTransferService.list(principal.getId(), status));
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAuthority('transaction:view')")
    @Operation(summary = "Get a transfer application in the current company")
    public ApiResponse<BankTransferResponse> transfer(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success(paymentTransferService.get(principal.getId(), id));
    }

    @GetMapping("/transfers/{id}/audit-events")
    @PreAuthorize("hasAuthority('transaction:view')")
    @Operation(summary = "Get the request-correlated transfer audit trail")
    public ApiResponse<List<PaymentTransferAuditResponse>> transferAudit(@PathVariable Long id,
                                                                          @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success(paymentTransferService.auditTrail(principal.getId(), id));
    }

    @PostMapping("/transfers/{id}/resolve")
    @PreAuthorize("hasAuthority('transfer:approve')")
    @Operation(summary = "Manually confirm the terminal result of an UNKNOWN transfer")
    public ApiResponse<BankTransferResponse> resolveTransfer(
            @PathVariable Long id,
            @Valid @RequestBody PaymentResolutionRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        ensureActivePaymentModuleEnabled();
        return ApiResponse.success("Unknown transfer resolved",
                paymentTransferService.resolveUnknown(principal.getId(), id, request, requestId));
    }

    private void ensureActivePaymentModuleEnabled() {
        if (!activePaymentEnabled) {
            throw new com.finance.system.common.exception.BusinessException(404,
                    "主动转账/支付不属于当前产品范围");
        }
    }
}
