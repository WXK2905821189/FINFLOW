package com.finance.system.bankdata.dto;

import java.util.List;

/**
 * End-to-end provenance chain for one synchronization: task, raw summaries,
 * normalized records and projection availability, all scoped to one company.
 */
public record BankDataTraceResponse(
        BankDataSyncTaskResponse task,
        List<BankDataRawSummaryResponse> rawSummaries,
        long statementCount,
        long balanceCount,
        boolean projectionAvailable,
        List<BankDataSyncLogResponse> logs,
        String note
) {
}
