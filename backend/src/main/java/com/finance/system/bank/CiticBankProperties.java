package com.finance.system.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank.citic")
public class CiticBankProperties {

    private boolean mockMode = true;
    private String baseUrl;
    private String appId;

    public boolean isMockMode() { return mockMode; }
    public void setMockMode(boolean mockMode) { this.mockMode = mockMode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
}
