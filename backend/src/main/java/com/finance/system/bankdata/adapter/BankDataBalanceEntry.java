package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataBalanceEntry(
        String bankRequestNo,
        Long bankAccountId,
        BigDecimal availableBalance,
        String currency,
        LocalDateTime asOfTime
) {
}
