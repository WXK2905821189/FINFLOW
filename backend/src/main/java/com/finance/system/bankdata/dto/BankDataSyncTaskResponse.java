package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

public record BankDataSyncTaskResponse(
        Long id,
        String taskNo,
        String adapterCode,
        String connectionCode,
        Long bankAccountId,
        String requestId,
        String bankRequestNo,
        String status,
        Integer rawCount,
        Integer normalizedCount,
        Integer duplicateCount,
        Integer invalidCount,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
}
