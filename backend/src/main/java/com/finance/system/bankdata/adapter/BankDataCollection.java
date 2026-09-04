package com.finance.system.bankdata.adapter;

import java.util.List;

/**
 * One adapter invocation's harvest. {@code pageTotals} carries the bank's own debit/credit
 * aggregate for this page when the bank reports one (CMB Z1); it is null for adapters that
 * do not, which is why the 7-arg constructor stays as the compatibility path.
 */
public record BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                                 List<BankDataBalanceEntry> balances, boolean hasMore,
                                 String nextCursor, String bankStatusCode, String status,
                                 BankPageTotals pageTotals) {

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances, boolean hasMore,
                              String nextCursor, String bankStatusCode, String status) {
        this(bankRequestNo, entries, balances, hasMore, nextCursor, bankStatusCode, status, null);
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances) {
        this(bankRequestNo, entries, balances, false, null, "SUCCESS", "SUCCESS", null);
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries) {
        this(bankRequestNo, entries, List.of(), false, null, "SUCCESS", "SUCCESS", null);
    }
}
