package com.finance.system.operations.dto;

import java.util.List;

public record ConnectionOverviewResponse(
        boolean enabled,
        String status,
        String message,
        List<ConnectionSummaryResponse> connections
) {
}
