package com.finance.system.statement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StatementResponse(
        Long id,
        Long batchId,
        String statementNo,
        Long bankAccountId,
        LocalDateTime transactionTime,
        String direction,
        BigDecimal amount,
        String currency,
        String counterpartyName,
        String maskedCounterpartyAccount,
        String summary,
        String validationStatus,
        String validationMessage,
        String reviewStatus,
        String reviewComment,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String pushStatus,
        String voucherNo,
        String pushMessage,
        LocalDateTime pushedAt,
        LocalDateTime createdAt
) {
}
