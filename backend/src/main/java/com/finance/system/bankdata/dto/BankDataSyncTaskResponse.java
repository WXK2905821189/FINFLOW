package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataSyncTaskResponse(
        Long id,
        String taskNo,
        String adapterCode,
        String mappingVersion,
        String connectionCode,
        Long bankAccountId,
        String requestId,
        String bankRequestNo,
        String status,
        Integer rawCount,
        Integer normalizedCount,
        Integer duplicateCount,
        Integer invalidCount,
        /** 银行 Z1 口径借方合计（带符号）；银行不报时为 null —— null 与 0 语义不同。 */
        BigDecimal debitAmount,
        Integer debitNums,
        BigDecimal creditAmount,
        Integer creditNums,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
}
