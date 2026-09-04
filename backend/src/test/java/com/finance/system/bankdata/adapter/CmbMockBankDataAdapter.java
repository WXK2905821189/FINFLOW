package com.finance.system.bankdata.adapter;

/** Test-only CMB brand fixture; production code ships no simulated adapters (mock-clean 2026-09-04). */
public class CmbMockBankDataAdapter extends BrandMockBankDataAdapter {

    public CmbMockBankDataAdapter() {
        super("CMB_MOCK", "CMB", "IN", "OUT");
    }
}
