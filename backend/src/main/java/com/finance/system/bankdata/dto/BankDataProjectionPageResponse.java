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
 *
 * <p>W8 曾加过服务端全量金额合计 {@code totals}，W9 按用户拍板整体退役（前端不消费、
 * 每次分页多打 SUM 不值得）；需要时从 git 历史找回。</p>
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
