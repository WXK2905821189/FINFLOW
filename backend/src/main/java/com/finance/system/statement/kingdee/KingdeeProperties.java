package com.finance.system.statement.kingdee;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kingdee integration settings.
 *
 * <p>Three-way gateway routing (mutually exclusive beans):
 * <ul>
 *   <li>{@code kingdee.mock-mode=true} (default) &rarr; {@link MockKingdeeVoucherGateway}</li>
 *   <li>{@code kingdee.mock-mode=false} + {@code kingdee.real-enabled!=true} &rarr;
 *       {@link UnavailableKingdeeVoucherGateway} (fail-closed, same behaviour as 2026-09-03)</li>
 *   <li>{@code kingdee.mock-mode=false} + {@code kingdee.real-enabled=true} &rarr;
 *       {@code RealKingdeeVoucherGateway} (kingdee-sdk Maven profile, official K3Cloud WebAPI SDK)</li>
 * </ul>
 *
 * <p>The real gateway targets cashier bills (cashier management): expense statements push
 * {@code AP_PAYBILL} (payment bill), income statements push {@code AR_RECEIVEBILL} (receipt bill).
 * Decision recorded 2026-09-04; GL_VOUCHER direct voucher push is intentionally not implemented.
 *
 * <p>Credential fields mirror the official SDK X-KDApi-* headers (X-KDApi-ServerUrl / AcctID /
 * AppID / AppSec / UserName / LCID). Secrets must only be provided via environment variables;
 * the CI release-contract rejects committed Kingdee credentials.
 */
@ConfigurationProperties(prefix = "kingdee")
public class KingdeeProperties {

    /** Legacy mock switch, kept for backward compatibility with KINGDEE_MOCK_MODE deployments. */
    private Boolean mockMode = Boolean.TRUE;

    /** Master switch for the real SDK gateway; requires the kingdee-sdk Maven profile. */
    private Boolean realEnabled = Boolean.FALSE;

    /** X-KDApi-ServerUrl: public-cloud gateway (e.g. https://apiexp.open.kingdee.com/k3cloud/) or private K3Cloud. */
    private String serverUrl;

    /** X-KDApi-AcctID: target data center id. */
    private String acctId;

    /** X-KDApi-AppID from third-party application authorization. */
    private String appId;

    /** X-KDApi-AppSec from third-party application authorization; never log in plain text. */
    private String appSec;

    /** X-KDApi-UserName: authorized integration user. */
    private String userName;

    /** X-KDApi-LCID: 2052 = zh-CN. */
    private Integer lcid = 2052;

    /** Organization FNumber used for bill head org references (demo env: 100). */
    private String orgNumber = "100";

    private String payBillFormId = "AP_PAYBILL";

    private String receiveBillFormId = "AR_RECEIVEBILL";

    /** Currency FNumber for bill head/settlement (PRE001 = CNY in demo data). */
    private String currencyNumber = "PRE001";

    /** Settlement type FNumber (电汇 = JSFS04_SYS in demo data; verified 2026-09-04). */
    private String settleTypeNumber = "JSFS04_SYS";

    /**
     * Auto-provision BD_Customer/BD_Supplier from the statement counterparty name when the
     * base-data lookup misses (calibrated 2026-09-07: minimal save = number/name/org, and a
     * duplicate save is an idempotent no-op signal). When false, an unresolved counterparty
     * fails the push (previous behaviour).
     */
    private Boolean autoCreateCounterparty = Boolean.TRUE;

    /** Prefix for generated counterparty FNumbers: prefix + first 10 hex chars of SHA-256(name). */
    private String counterpartyNumberPrefix = "FINFLW";

    /** Customer-type FNumber required by BD_Customer creation (demo env: KHLB001_SYS); blank = omit. */
    private String customerTypeNumber = "KHLB001_SYS";

    /**
     * Auto submit+audit the bill after a successful save (calibrated 2026-09-07: submit
     * verified on the demo env; audit request contract confirmed, completion blocked by
     * apiexp DB outage). Default OFF — whether the Kingdee-side approval flow may be
     * bypassed is a business decision to confirm at joint test. A bill is always left
     * SAVED (audit failure never fails the push or triggers a re-push duplicate).
     */
    private Boolean autoAudit = Boolean.FALSE;

    /**
     * FINFLOW bank account number &rarr; Kingdee bank account FNumber (CN_BANKACNT) mapping
     * default. Per-account mapping belongs to the bank account registry (联调期校准); this
     * fallback keeps the payload builder testable before that mapping exists.
     */
    private String defaultBankAccountNumber;

    public boolean isRealMode() {
        return !Boolean.TRUE.equals(mockMode) && Boolean.TRUE.equals(realEnabled);
    }

    public Boolean getMockMode() {
        return mockMode;
    }

    public void setMockMode(Boolean mockMode) {
        this.mockMode = mockMode;
    }

    public Boolean getRealEnabled() {
        return realEnabled;
    }

    public void setRealEnabled(Boolean realEnabled) {
        this.realEnabled = realEnabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getAcctId() {
        return acctId;
    }

    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSec() {
        return appSec;
    }

    public void setAppSec(String appSec) {
        this.appSec = appSec;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Integer getLcid() {
        return lcid;
    }

    public void setLcid(Integer lcid) {
        this.lcid = lcid;
    }

    public String getOrgNumber() {
        return orgNumber;
    }

    public void setOrgNumber(String orgNumber) {
        this.orgNumber = orgNumber;
    }

    public String getPayBillFormId() {
        return payBillFormId;
    }

    public void setPayBillFormId(String payBillFormId) {
        this.payBillFormId = payBillFormId;
    }

    public String getReceiveBillFormId() {
        return receiveBillFormId;
    }

    public void setReceiveBillFormId(String receiveBillFormId) {
        this.receiveBillFormId = receiveBillFormId;
    }

    public String getCurrencyNumber() {
        return currencyNumber;
    }

    public void setCurrencyNumber(String currencyNumber) {
        this.currencyNumber = currencyNumber;
    }

    public String getDefaultBankAccountNumber() {
        return defaultBankAccountNumber;
    }

    public void setDefaultBankAccountNumber(String defaultBankAccountNumber) {
        this.defaultBankAccountNumber = defaultBankAccountNumber;
    }

    public String getSettleTypeNumber() {
        return settleTypeNumber;
    }

    public void setSettleTypeNumber(String settleTypeNumber) {
        this.settleTypeNumber = settleTypeNumber;
    }

    public Boolean getAutoCreateCounterparty() {
        return autoCreateCounterparty;
    }

    public void setAutoCreateCounterparty(Boolean autoCreateCounterparty) {
        this.autoCreateCounterparty = autoCreateCounterparty;
    }

    public String getCounterpartyNumberPrefix() {
        return counterpartyNumberPrefix;
    }

    public void setCounterpartyNumberPrefix(String counterpartyNumberPrefix) {
        this.counterpartyNumberPrefix = counterpartyNumberPrefix;
    }

    public String getCustomerTypeNumber() {
        return customerTypeNumber;
    }

    public void setCustomerTypeNumber(String customerTypeNumber) {
        this.customerTypeNumber = customerTypeNumber;
    }

    public Boolean getAutoAudit() {
        return autoAudit;
    }

    public void setAutoAudit(Boolean autoAudit) {
        this.autoAudit = autoAudit;
    }
}
