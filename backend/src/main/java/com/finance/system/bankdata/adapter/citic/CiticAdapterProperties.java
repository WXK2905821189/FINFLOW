package com.finance.system.bankdata.adapter.citic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the authorized CITIC bank data adapter.
 *
 * <p>Behavior keys drive {@code RealCiticBankDataAdapter}; SDK keys are placeholders for the
 * future {@code SdkCiticDlinkSdk} transport (aligned with citicbank-sdk.properties in the
 * vendor demo). Every real-call capability stays disabled by default.</p>
 */
@ConfigurationProperties(prefix = "bankdata.adapter.citic")
public class CiticAdapterProperties {

    /** Master switch for the REAL adapter bean; the bank adapter stays MOCK unless enabled. */
    private boolean realEnabled = false;

    /** DLTRNALL startRecord base (0 or 1). Vendor docs do not state it; verify during joint testing. */
    private int startRecordBase = 1;

    /** DLTRNALL hard page size (vendor limit: at most 20 per request). */
    private int pageSize = 20;

    /** DLTRNALL controlFlag; 2 returns oriNum (raw serial number) used as an idempotency key. */
    private int controlFlag = 2;

    /** Vendor test limit for query-type transactions; only recorded here as an operational note. */
    private int queryRateLimitPerHour = 400;

    private final Sdk sdk = new Sdk();

    public boolean isRealEnabled() {
        return realEnabled;
    }

    public void setRealEnabled(boolean realEnabled) {
        this.realEnabled = realEnabled;
    }

    public int getStartRecordBase() {
        return startRecordBase;
    }

    public void setStartRecordBase(int startRecordBase) {
        this.startRecordBase = startRecordBase;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = Math.max(1, Math.min(20, pageSize));
    }

    public int getControlFlag() {
        return controlFlag;
    }

    public void setControlFlag(int controlFlag) {
        this.controlFlag = controlFlag;
    }

    public int getQueryRateLimitPerHour() {
        return queryRateLimitPerHour;
    }

    public void setQueryRateLimitPerHour(int queryRateLimitPerHour) {
        this.queryRateLimitPerHour = queryRateLimitPerHour;
    }

    public Sdk getSdk() {
        return sdk;
    }

    /** Vendor SDK property keys; names intentionally mirror citicbank-sdk.properties. */
    public static class Sdk {

        private String url;
        private String orgCode;
        private String userName;
        private String cashFlag = "0";
        private String certPath;
        /** One-time download code issued by the bank; invalid after first use. */
        private String downloadCode;
        private String hostIp;
        private boolean openCommCustom = true;
        private String logPath;
        private String token;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getOrgCode() { return orgCode; }
        public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public String getCashFlag() { return cashFlag; }
        public void setCashFlag(String cashFlag) { this.cashFlag = cashFlag; }
        public String getCertPath() { return certPath; }
        public void setCertPath(String certPath) { this.certPath = certPath; }
        public String getDownloadCode() { return downloadCode; }
        public void setDownloadCode(String downloadCode) { this.downloadCode = downloadCode; }
        public String getHostIp() { return hostIp; }
        public void setHostIp(String hostIp) { this.hostIp = hostIp; }
        public boolean isOpenCommCustom() { return openCommCustom; }
        public void setOpenCommCustom(boolean openCommCustom) { this.openCommCustom = openCommCustom; }
        public String getLogPath() { return logPath; }
        public void setLogPath(String logPath) { this.logPath = logPath; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
