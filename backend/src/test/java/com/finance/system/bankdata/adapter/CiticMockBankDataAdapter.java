package com.finance.system.bankdata.adapter;

/** Test-only CITIC brand fixture; production code ships no simulated adapters (mock-clean 2026-09-04). */
public class CiticMockBankDataAdapter extends BrandMockBankDataAdapter {

    public CiticMockBankDataAdapter() {
        super("CITIC_MOCK", "CITIC", "C", "D");
    }
}
