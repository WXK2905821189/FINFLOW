package com.finance.system.bankdata.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bankdata.adapter", name = "mode", havingValue = "mock", matchIfMissing = true)
public class CiticMockBankDataAdapter extends BrandMockBankDataAdapter {

    public CiticMockBankDataAdapter() {
        super("CITIC_MOCK", "CITIC", "C", "D");
    }
}
