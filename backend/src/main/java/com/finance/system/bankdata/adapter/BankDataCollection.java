package com.finance.system.bankdata.adapter;

import java.util.List;

public record BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                                 List<BankDataBalanceEntry> balances, boolean hasMore,
                                 String nextCursor, String bankStatusCode, String status) {

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries,
                              List<BankDataBalanceEntry> balances) {
        this(bankRequestNo, entries, balances, false, null, "SUCCESS", "SUCCESS");
    }

    public BankDataCollection(String bankRequestNo, List<BankDataEntry> entries) {
        this(bankRequestNo, entries, List.of(), false, null, "SUCCESS", "SUCCESS");
    }
}
