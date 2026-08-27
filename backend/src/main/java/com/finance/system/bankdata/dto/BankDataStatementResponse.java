package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataStatementResponse(
        Long id,
        Long taskId,
        Long rawMessageId,
        String contentSha256,
        LocalDateTime retentionUntil,
        Long bankAccountId,
        String bankRequestNo,
        String statementNo,
        LocalDateTime transactionTime,
        String direction,
        BigDecimal amount,
        String currency,
        String counterpartyName,
        String counterpartyAccountMasked,
        String summary,
        String validationStatus,
        String validationMessage,
        LocalDateTime createdAt
) {
}
