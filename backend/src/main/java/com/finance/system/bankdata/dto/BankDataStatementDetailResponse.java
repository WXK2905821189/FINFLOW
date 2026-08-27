package com.finance.system.bankdata.dto;

import java.util.List;

public record BankDataStatementDetailResponse(
        BankDataStatementResponse statement,
        BankDataSyncTaskResponse task,
        List<BankDataSyncLogResponse> logs
) {
}
