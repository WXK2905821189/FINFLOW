package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Safe projection row for bank data. Lineage fields (mock-clean workstream 2026-09-04):
 * {@code bankRequestNo} is the bank-issued request id that produced this row and
 * {@code taskStatus} is the producing sync task's status (e.g. SUCCEEDED / UNKNOWN) so the
 * UI can mark data from unresolved tasks as pending verification. The former always-false
 * {@code simulated} flag is gone — production ships no simulated data path at all.
 */
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
        String bankRequestNo,
        String taskStatus
) {
}
