package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One normalized bank statement row produced by an adapter.
 *
 * <p>The first ten components are the FINFLOW accounting projection (unsigned {@code amount}
 * plus an {@code INCOME}/{@code EXPENSE} direction) and are mandatory for every adapter.
 * {@code vendor} carries the bank's own fields verbatim and may be {@code null} for adapters
 * that do not report them — see {@link VendorStatementFields}.</p>
 */
public record BankDataEntry(
        String bankRequestNo,
        String statementNo,
        Long bankAccountId,
        LocalDateTime transactionTime,
        String direction,
        BigDecimal amount,
        String currency,
        String counterpartyName,
        String counterpartyAccount,
        String summary,
        /** Bank-native fields as returned by the vendor; null for adapters without vendor detail. */
        VendorStatementFields vendor
) {

    /** Compact constructor for adapters (and tests) that report no vendor detail. */
    public BankDataEntry(String bankRequestNo,
                         String statementNo,
                         Long bankAccountId,
                         LocalDateTime transactionTime,
                         String direction,
                         BigDecimal amount,
                         String currency,
                         String counterpartyName,
                         String counterpartyAccount,
                         String summary) {
        this(bankRequestNo, statementNo, bankAccountId, transactionTime, direction, amount,
                currency, counterpartyName, counterpartyAccount, summary, null);
    }
}
