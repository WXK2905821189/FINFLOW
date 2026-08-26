package com.finance.system.bank.dto;

import java.math.BigDecimal;

public record BankAccountResponse(
        Long id,
        String bankCode,
        String accountName,
        String maskedAccountNumber,
        String currency,
        BigDecimal availableBalance,
        String status
) {
}
