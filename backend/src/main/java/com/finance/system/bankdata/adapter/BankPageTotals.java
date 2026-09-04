package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;

/**
 * The bank's own per-page debit/credit totals from CMB {@code trsQryByBreakPoint} Z1
 * ({@code debitNums / debitAmount / creditNums / creditAmount}). Amounts keep the bank's
 * sign (debit negative, credit positive); CITIC reports no equivalent, so it stays null.
 *
 * <p>The sync executor sums these across pages and windows onto the sync task, giving a
 * window-level reconciliation figure straight from the bank — independent of what FINFLOW
 * counted after dedup and validation. The raw payload of every contributing page stays in
 * the raw message module, so the sum stays auditable.</p>
 */
public record BankPageTotals(BigDecimal debitAmount, Long debitNums,
                             BigDecimal creditAmount, Long creditNums) {
}
