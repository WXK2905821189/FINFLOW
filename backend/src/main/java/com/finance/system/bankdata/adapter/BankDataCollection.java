package com.finance.system.bankdata.adapter;

import java.util.List;

/**
 * One adapter invocation's harvest. {@code pageTotals} carries the bank's own debit/credit
 * aggregate for this page when the bank reports one (CMB Z1); it is null for adapters that
 * do not, which is why the 7-arg constructor stays as the compatibility path.
 *
 * <p>{@code evidence} carries the wire-level facts (raw response text, request facts) for
 * the ODS layer; it is null for adapters that do not capture it and for terminal/UNKNOWN
 * results. The sync executor serializes the parsed view through {@link #withoutEvidence()}
 * so the compact view payload stays byte-compatible with rows captured before evidence
 * existed.</p>
 */
public record BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                                 List<BankDataBalanceEntry> balances, boolean hasMore,
                                 String nextCursor, String bankStatusCode, String status,
                                 BankPageTotals pageTotals, BankExchangeEvidence evidence) {

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances, boolean hasMore,
                              String nextCursor, String bankStatusCode, String status,
                              BankPageTotals pageTotals) {
        this(bankRequestNo, entries, balances, hasMore, nextCursor, bankStatusCode, status,
                pageTotals, null);
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances, boolean hasMore,
                              String nextCursor, String bankStatusCode, String status) {
        this(bankRequestNo, entries, balances, hasMore, nextCursor, bankStatusCode, status, null, null);
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances) {
        this(bankRequestNo, entries, balances, false, null, "SUCCESS", "SUCCESS", null, null);
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries) {
        this(bankRequestNo, entries, List.of(), false, null, "SUCCESS", "SUCCESS", null, null);
    }

    /** The parsed view as it was stored before evidence existed — evidence excluded. */
    public BankDataCollection withoutEvidence() {
        return new BankDataCollection(bankRequestNo, entries, balances, hasMore, nextCursor,
                bankStatusCode, status, pageTotals, null);
    }
}
