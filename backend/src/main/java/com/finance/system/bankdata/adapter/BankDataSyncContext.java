package com.finance.system.bankdata.adapter;

public record BankDataSyncContext(
        Long companyId,
        Long connectionId,
        Long bankAccountId,
        String taskNo,
        String requestId
) {
}
