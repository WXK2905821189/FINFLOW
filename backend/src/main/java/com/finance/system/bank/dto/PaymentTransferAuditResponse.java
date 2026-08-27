package com.finance.system.bank.dto;

import java.time.LocalDateTime;

public record PaymentTransferAuditResponse(
        Long id,
        String requestId,
        String action,
        String previousStatus,
        String currentStatus,
        Long operatorId,
        String detail,
        LocalDateTime createdAt
) {
}
