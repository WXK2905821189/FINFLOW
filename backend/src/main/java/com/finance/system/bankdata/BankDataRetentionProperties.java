package com.finance.system.bankdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bankdata.retention")
public class BankDataRetentionProperties {

    private boolean cleanupEnabled = false;
    private int batchLimit = 100;

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public int getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(int batchLimit) {
        this.batchLimit = batchLimit;
    }

    public int boundedBatchLimit() {
        return Math.min(1000, Math.max(1, batchLimit));
    }
}
