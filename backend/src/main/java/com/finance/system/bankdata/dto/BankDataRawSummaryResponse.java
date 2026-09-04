package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

/**
 * Safe provenance summary of one persisted raw bank response.
 * It never carries the raw payload, bank-specific fields or credentials.
 */
public record BankDataRawSummaryResponse(
        Long id,
        String bankRequestNo,
        String contentSha256,
        String adapterCode,
        String mappingVersion,
        LocalDateTime receivedAt,
        LocalDateTime retentionUntil
) {
}
