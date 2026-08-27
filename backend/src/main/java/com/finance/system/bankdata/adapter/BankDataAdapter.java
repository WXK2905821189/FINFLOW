package com.finance.system.bankdata.adapter;

public interface BankDataAdapter {

    String adapterCode();

    BankDataCollection collect(BankDataSyncContext context);
}
