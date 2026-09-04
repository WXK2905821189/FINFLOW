package com.finance.system.bankdata.dto;

import java.util.List;

public record BankDataSyncTaskDetailResponse(
        BankDataSyncTaskResponse task,
        List<BankDataSyncLogResponse> logs
) {
}
