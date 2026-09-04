package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

public record BankSyncJobEventResponse(
        String status,
        String stage,
        String message,
        String requestId,
        LocalDateTime occurredAt
) {
}
