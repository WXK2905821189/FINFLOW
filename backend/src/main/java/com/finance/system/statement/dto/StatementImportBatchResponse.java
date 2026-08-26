package com.finance.system.statement.dto;

import java.time.LocalDateTime;

public record StatementImportBatchResponse(
        Long id,
        String batchNo,
        String sourceType,
        String sourceName,
        String status,
        Integer totalCount,
        Integer importedCount,
        Integer duplicateCount,
        Integer invalidCount,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        String errorMessage
) {
}
