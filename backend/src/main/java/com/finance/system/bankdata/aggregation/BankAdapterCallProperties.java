package com.finance.system.bankdata.aggregation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Defaults deliberately keep every real bank adapter unavailable. */
@ConfigurationProperties(prefix = "bankdata.adapter.call")
public class BankAdapterCallProperties {

    private boolean realAdaptersEnabled = false;
    private int maxAttempts = 3;
    private long timeoutMillis = 5_000;
    private long baseBackoffMillis = 200;
    private int maxRequestsPerMinute = 60;

    public boolean isRealAdaptersEnabled() { return realAdaptersEnabled; }
    public void setRealAdaptersEnabled(boolean realAdaptersEnabled) { this.realAdaptersEnabled = realAdaptersEnabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, maxAttempts); }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = Math.max(1, timeoutMillis); }
    public long getBaseBackoffMillis() { return baseBackoffMillis; }
    public void setBaseBackoffMillis(long baseBackoffMillis) { this.baseBackoffMillis = Math.max(0, baseBackoffMillis); }
    public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
    public void setMaxRequestsPerMinute(int maxRequestsPerMinute) { this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute); }
}
