package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Safe page envelope for bank-data queries; it never contains raw bank payloads.
 *
 * <p>The record type {@code T} is the bank's own row shape ({@link BankDataBalanceResponse} or
 * {@link BankDataStatementResponse}) rather than a generic business projection: the balance and
 * statement screens show the fields the bank actually returned, so inventing a shared
 * "id / occurredAt / amount / direction" shape on top of them only hid information.</p>
 */
public record BankDataProjectionPageResponse<T>(
        long page,
        long size,
        long total,
        List<T> records,
        boolean enabled,
        String status,
        String message,
        String requestId,
        String sourceSystem,
        LocalDateTime lastSyncedAt
) {

    public static <T> BankDataProjectionPageResponse<T> empty(int page, int size, String status,
                                                              String message, boolean enabled) {
        return new BankDataProjectionPageResponse<>(Math.max(1, page), Math.min(100, Math.max(1, size)),
                0, List.of(), enabled, status, message, null, "BANKDATA", null);
    }
}
