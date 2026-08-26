package com.finance.system.statement.dto;

import java.time.LocalDateTime;

public record StatementAuditEventResponse(
        Long id,
        String action,
        String result,
        String previousStatus,
        String currentStatus,
        Long operatorId,
        String detail,
        LocalDateTime createdAt
) {
}
