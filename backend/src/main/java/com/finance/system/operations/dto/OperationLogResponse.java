package com.finance.system.operations.dto;

import java.time.LocalDateTime;

public record OperationLogResponse(
        Long taskId,
        String level,
        String eventType,
        String result,
        String requestId,
        String message,
        LocalDateTime occurredAt
) {
}
