package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bank-attested totals (CMB Z1, stored on the sync task) side by side with what the
 * platform actually normalized for the same task.
 *
 * <p>{@code countConsistent}/{@code amountConsistent} are null when the bank reported
 * no Z1 totals for the window (null means "the bank says nothing", which must stay
 * distinguishable from "consistent" and from "inconsistent"). Counts compare the
 * bank's debit/credit nums against platform EXPENSE/INCOME row counts; amounts compare
 * the bank's unsigned debit/credit sums against platform sums of unsigned amounts.</p>
 */
public record BankTaskReconciliationResponse(
        Long taskId,
        String taskNo,
        String adapterCode,
        String status,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Integer bankDebitNums,
        BigDecimal bankDebitAmount,
        Integer bankCreditNums,
        BigDecimal bankCreditAmount,
        long platformExpenseCount,
        long platformIncomeCount,
        BigDecimal platformExpenseAmount,
        BigDecimal platformIncomeAmount,
        Boolean countConsistent,
        Boolean amountConsistent
) {
}
