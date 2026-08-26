package com.finance.system.operations.dto;

import java.time.LocalDateTime;

public record ConnectionSummaryResponse(
        String connectionCode,
        String displayName,
        String providerType,
        boolean enabled,
        String status,
        LocalDateTime lastCheckedAt
) {
}
