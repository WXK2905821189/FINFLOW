package com.finance.system.bankdata.adapter.citic;

import java.util.List;

/** DLBALQRY container-level result: transaction status plus account-level rows. */
public record CiticBalanceResult(String status, String statusText, List<CiticBalanceRow> rows) {
}
