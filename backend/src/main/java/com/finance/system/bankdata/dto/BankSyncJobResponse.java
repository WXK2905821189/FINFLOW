package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

public record BankSyncJobResponse(
        Long id,
        String jobNo,
        String jobType,
        String triggerType,
        String connectionCode,
        String status,
        String requestId,
        String summary,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
}
