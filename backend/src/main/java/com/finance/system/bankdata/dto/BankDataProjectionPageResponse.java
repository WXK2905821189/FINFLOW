package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Safe page envelope for bank-data queries; it never contains raw bank payloads.
 *
 * <p>The record type {@code T} is the bank's own row shape ({@link BankDataBalanceResponse} or
 * {@link BankDataStatementResponse}) rather than a generic business projection: the balance and
 * statement screens show the fields the bank actually returned, so inventing a shared
 * "id / occurredAt / amount / direction" shape on top of them only hid information.</p>
 *
 * <p>W8：{@code totals} 为**服务端全量金额合计**（与本次查询完全相同的 WHERE 条件下聚合，
 * 随筛选/时间窗实时变化），键随 resource 而异——
 * 余额页：{@code availableBalance / onlineBalance / frozenBalance}；
 * 流水页：{@code debitAmount / creditAmount / signedAmount}。
 * 仅 REAL 正常返回时携带，空页/未连接页为 null。</p>
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
        LocalDateTime lastSyncedAt,
        Map<String, BigDecimal> totals
) {

    /** 兼容构造器：W8 之前的三处调用点零改动。 */
    public BankDataProjectionPageResponse(long page, long size, long total, List<T> records,
                                          boolean enabled, String status, String message,
                                          String requestId, String sourceSystem,
                                          LocalDateTime lastSyncedAt) {
        this(page, size, total, records, enabled, status, message, requestId, sourceSystem,
                lastSyncedAt, null);
    }

    public static <T> BankDataProjectionPageResponse<T> empty(int page, int size, String status,
                                                              String message, boolean enabled) {
        return new BankDataProjectionPageResponse<>(Math.max(1, page), Math.min(100, Math.max(1, size)),
                0, List.of(), enabled, status, message, null, "BANKDATA", null);
    }
}
