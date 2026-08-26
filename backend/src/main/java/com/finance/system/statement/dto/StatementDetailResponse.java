package com.finance.system.statement.dto;

import java.util.List;

public record StatementDetailResponse(
        StatementResponse statement,
        List<StatementAuditEventResponse> auditTrail
) {
}
