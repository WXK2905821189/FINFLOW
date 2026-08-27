package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

public record BankDataConnectionResponse(
        String connectionCode,
        String displayName,
        String providerType,
        boolean enabled,
        String status,
        LocalDateTime lastCheckedAt,
        String credentialHandling
) {
}
