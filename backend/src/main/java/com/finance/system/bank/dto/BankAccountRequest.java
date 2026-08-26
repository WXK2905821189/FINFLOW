package com.finance.system.bank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BankAccountRequest(
        @NotBlank(message = "Bank code is required") @Size(max = 32) String bankCode,
        @NotBlank(message = "Account name is required") @Size(max = 128) String accountName,
        @NotBlank(message = "Account number is required") @Pattern(regexp = "^[0-9A-Za-z]{8,64}$", message = "Account number format is invalid") String accountNumber,
        @NotBlank(message = "Currency is required") @Size(min = 3, max = 3) String currency,
        @NotNull(message = "Available balance is required") @DecimalMin(value = "0.00", message = "Available balance must not be negative") BigDecimal availableBalance,
        @NotBlank(message = "Status is required") String status
) {
}
