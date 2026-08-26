package com.finance.system.bank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BankTransferRequest(
        @NotBlank(message = "Bank code is required") String bankCode,
        @NotNull(message = "Payer account id is required") Long payerAccountId,
        @NotBlank(message = "Payee name is required") @Size(max = 128) String payeeName,
        @NotBlank(message = "Payee account is required") @Pattern(regexp = "^[0-9A-Za-z]{8,64}$", message = "Payee account format is invalid") String payeeAccount,
        @NotBlank(message = "Payee bank is required") @Size(max = 128) String payeeBank,
        @NotNull(message = "Amount is required") @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
        @NotBlank(message = "Remark is required") @Size(max = 255) String remark
) {
}
