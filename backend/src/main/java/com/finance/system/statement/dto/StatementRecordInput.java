package com.finance.system.statement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Unified input contract used by file and simulated statement collectors. */
public record StatementRecordInput(
        String statementNo,
        Long bankAccountId,
        LocalDateTime transactionTime,
        String direction,
        BigDecimal amount,
        String currency,
        String counterpartyName,
        String counterpartyAccount,
        String summary
) {
}
