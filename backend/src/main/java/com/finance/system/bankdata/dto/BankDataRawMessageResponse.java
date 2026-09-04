package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

/**
 * One captured bank response, listed without its payload.
 *
 * <p>The payload stays out of list responses on purpose: a statement batch can be
 * large, and a list only needs enough to answer "did we reach the bank, when, and
 * through which adapter". {@code realDirect} is that answer - it is true only when
 * the message was produced by a REAL-mode adapter, so it doubles as the
 * connectivity evidence the raw message module exists to provide.
 *
 * <p>Digests and retention metadata are safe to expose; the payload itself is only
 * available through the detail endpoint, which is guarded by the same permission.
 */
public record BankDataRawMessageResponse(
        Long id,
        Long taskId,
        String taskNo,
        Long bankAccountId,
        String adapterCode,
        String bankRequestNo,
        String contentSha256,
        LocalDateTime receivedAt,
        LocalDateTime retentionUntil,
        LocalDateTime purgedAt,
        boolean realDirect
) {
}
