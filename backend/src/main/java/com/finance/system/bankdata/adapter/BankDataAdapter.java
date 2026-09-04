package com.finance.system.bankdata.adapter;

public interface BankDataAdapter {

    String adapterCode();

    BankDataCollection collect(BankDataSyncContext context);

    /** Existing and test adapters stay simulated unless a future adapter explicitly opts in. */
    default BankAdapterExecutionMode executionMode() {
        return BankAdapterExecutionMode.SIMULATED;
    }
}
