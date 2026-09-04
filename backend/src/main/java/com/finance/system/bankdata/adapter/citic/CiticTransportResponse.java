package com.finance.system.bankdata.adapter.citic;

/** Transport and bank-business status are deliberately separate for reconciliation handling. */
public record CiticTransportResponse(
        int httpStatus,
        String transportCode,
        String businessCode,
        String bankRequestNo,
        String encodedBody
) {
}
