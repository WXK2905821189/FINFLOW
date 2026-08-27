package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataBalanceResponse(
        Long id,
        Long taskId,
        Long rawMessageId,
        String contentSha256,
        LocalDateTime retentionUntil,
        Long bankAccountId,
        String accountMasked,
        String bankRequestNo,
        BigDecimal availableBalance,
        String currency,
        LocalDateTime asOfTime,
        String validationStatus,
        String validationMessage,
        LocalDateTime createdAt
) {
}
