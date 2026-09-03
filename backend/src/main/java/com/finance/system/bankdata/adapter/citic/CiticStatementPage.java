package com.finance.system.bankdata.adapter.citic;

import java.util.List;

/**
 * DLTRNALL container-level response: transaction status plus paging summary.
 * Account-level rows follow the container fields inside the userDataList domain.
 */
public record CiticStatementPage(
        String status,
        String statusText,
        String accountNo,
        String accountName,
        Integer totalRecords,
        Integer returnRecords,
        List<CiticStatementRow> rows
) {
}
