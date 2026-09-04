package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataEntry(
        String bankRequestNo,
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
