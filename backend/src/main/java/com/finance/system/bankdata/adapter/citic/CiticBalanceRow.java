package com.finance.system.bankdata.adapter.citic;

import java.math.BigDecimal;

/**
 * DLBALQRY response row (account-level). Fields mirror the vendor XML names
 * (including the vendor's original {@code forzenAmt} spelling).
 */
public record CiticBalanceRow(
        String status,
        String statusText,
        String accountNo,
        String accountName,
        String currencyId,
        String openBankName,
        String lastTranDate,
        BigDecimal usableBalance,
        BigDecimal balance,
        BigDecimal forzenAmt
) {
}
