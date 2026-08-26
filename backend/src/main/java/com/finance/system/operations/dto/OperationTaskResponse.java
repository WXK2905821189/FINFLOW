package com.finance.system.operations.dto;

import java.time.LocalDateTime;

public record OperationTaskResponse(
        String taskNo,
        String taskType,
        String connectionCode,
        String status,
        String requestId,
        String summary,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
}
