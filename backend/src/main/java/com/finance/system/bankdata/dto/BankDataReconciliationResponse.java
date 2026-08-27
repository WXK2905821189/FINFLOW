package com.finance.system.bankdata.dto;

import java.math.BigDecimal;

public record BankDataReconciliationResponse(
        long statementCount,
        long validCount,
        long duplicateCount,
        long invalidCount,
        BigDecimal totalAmount,
        BigDecimal incomeAmount,
        BigDecimal expenseAmount,
        long taskCount,
        long failedTaskCount
) {
}
