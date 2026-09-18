package com.finance.system.bank.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of one read-only bank connectivity test
 * ({@code POST /api/bank-accounts/{id}/test-connection}).
 *
 * <p>The probe calls the account's adapter once through the aggregation call executor
 * (same rate limit / timeout / retry semantics as real syncs) and reports what the bank
 * returned. Nothing is persisted: no sync task, no projection row, no raw message —
 * only this sanitized summary goes back to the UI. Raw payloads and credentials never
 * appear in any field.</p>
 *
 * @param result           CONNECTED when the bank answered a successful exchange;
 *                         FAILED when the bank answered with a non-success code;
 *                         DISABLED when real adapters are switched off, or when the
 *                         account's bank has no registered adapter in this deployment
 *                         (a probe result, not an error — see BankConnectionTestService);
 *                         TIMEOUT / PENDING from the call executor's bounded retry.
 * @param message          sanitized human-readable summary (no raw payloads, no secrets)
 * @param bankRequestNo    bank-side request number when an exchange happened
 * @param operation        primary exchange funcode (e.g. DLTRNALL / NTQADINF), for debuggability
 * @param durationMs       primary exchange duration in milliseconds
 * @param endpoint         bank gateway URL the exchange went to (debug visibility)
 * @param availableBalance bank-reported available balance when a balance snapshot came back
 * @param currency         currency of the balance snapshot
 * @param statementRows    normalized statement rows on the first page (0 = window empty is normal)
 * @param testedAt         server time the test finished
 */
public record BankConnectionTestResponse(
        String result,
        String message,
        String bankRequestNo,
        String operation,
        Long durationMs,
        String endpoint,
        BigDecimal availableBalance,
        String currency,
        Integer statementRows,
        LocalDateTime testedAt
) {
}
