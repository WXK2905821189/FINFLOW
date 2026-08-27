package com.finance.system.bankdata.adapter;

public record BankDataSyncContext(
        Long companyId,
        Long connectionId,
        Long bankAccountId,
        String taskNo,
        String requestId,
        java.time.LocalDateTime windowStart,
        java.time.LocalDateTime windowEnd,
        Integer pageNumber,
        String cursor,
        Integer pageSize,
        String resourceType
) {

    public BankDataSyncContext(Long companyId, Long connectionId, Long bankAccountId, String taskNo,
                               String requestId) {
        this(companyId, connectionId, bankAccountId, taskNo, requestId, null, null, 1, null, 100, "STATEMENT");
    }

    public BankDataSyncContext nextPage(int nextPageNumber, String nextCursor) {
        return new BankDataSyncContext(companyId, connectionId, bankAccountId, taskNo, requestId,
                windowStart, windowEnd, nextPageNumber, nextCursor, pageSize, resourceType);
    }
}
