package com.finance.system.statement.dto;

import java.math.BigDecimal;

public record StatementDashboardResponse(
        long totalCount,
        long pendingReviewCount,
        long approvedCount,
        long rejectedCount,
        long pushedCount,
        long invalidCount,
        BigDecimal totalAmount,
        BigDecimal approvedAmount,
        BigDecimal pushedAmount
) {
}
