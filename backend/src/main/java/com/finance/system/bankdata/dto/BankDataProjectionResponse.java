package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataProjectionResponse(
        String id,
        String sourceSystem,
        String sourceRecordId,
        String status,
        LocalDateTime occurredAt,
        String accountMasked,
        BigDecimal amount,
        String currency,
        String direction,
        String summary,
        String syncJobNo,
        String requestId,
        LocalDateTime lastSyncedAt,
        boolean simulated
) {
}
