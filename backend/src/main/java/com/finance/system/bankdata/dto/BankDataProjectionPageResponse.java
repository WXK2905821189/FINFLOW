package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Safe page envelope for bank-data projections; it never contains raw bank payloads. */
public record BankDataProjectionPageResponse(
        long page,
        long size,
        long total,
        List<BankDataProjectionResponse> records,
        boolean enabled,
        String status,
        String message,
        String requestId,
        String sourceSystem,
        LocalDateTime lastSyncedAt,
        boolean simulated
) {
}
