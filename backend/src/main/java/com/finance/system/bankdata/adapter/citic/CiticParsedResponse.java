package com.finance.system.bankdata.adapter.citic;

public record CiticParsedResponse(
        String transportStatus,
        String businessStatus,
        String bankRequestNo,
        boolean accepted,
        String safeSummary
) {
}
