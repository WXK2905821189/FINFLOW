package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankDataCollection;

/** A safely bounded Adapter invocation result, before vendor result normalization. */
record BankAdapterCallOutcome(BankDataCollection collection, BankDataStatus terminalStatus, String safeSummary) {

    static BankAdapterCallOutcome response(BankDataCollection collection) {
        return new BankAdapterCallOutcome(collection, null, null);
    }

    static BankAdapterCallOutcome terminal(BankDataStatus status, String summary) {
        return new BankAdapterCallOutcome(null, status, summary);
    }
}
