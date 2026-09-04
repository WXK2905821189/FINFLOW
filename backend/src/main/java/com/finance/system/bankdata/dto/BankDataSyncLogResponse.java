package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

public record BankDataSyncLogResponse(
        Long id,
        String level,
        String eventType,
        String result,
        String requestId,
        String bankRequestNo,
        String message,
        LocalDateTime createdAt
) {
}
