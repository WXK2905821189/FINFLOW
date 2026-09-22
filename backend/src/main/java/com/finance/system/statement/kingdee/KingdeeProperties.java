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

    /**
     * 「待确认兜底科目」编码（2026-09-21）。AI 建议的科目在账套里不存在、或置信度低于阈值时，
     * 该行科目替换为此编码 + 在推送消息里标注，保证凭证仍能推到金蝶，由人工在金蝶侧改成正确科目。
     *
     * <p>为什么不能「留空」：实测（2026-09-21，真实账套 400）金蝶对**无科目**的分录直接拒绝——
     * 传 {@code FACCOUNTID.FNumber=""} 或整个不传该键，都返回
     * 「请输入凭证数据，凭证分录不合法！」；对照用例（正常科目）保存成功。故必须用账套里**真实存在**
     * 的科目兜底。</p>
     *
     * <p>为什么默认是 {@code 2241.99} 而不是用户最初提的 {@code 2241}：实测 {@code 2241} 其他应付款是
     * **父科目**（{@code BD_Account.FIsDetail=false}），金蝶不允许直接记账到父科目（同样报「分录不合法」）；
     * 而 {@code 2241.99} 其他应付款-其他是**明细科目且不挂必录维度**（实测借 2241.99 / 贷 1001 保存成功，
     * 单号 16077，已即时删除）。{@code 1901} 待处理财产损溢 同样实测可用。</p>
     */
    private String fallbackAccount = "2241.99";

    /**
     * 低置信度阈值（0~1，默认 0.6）：AI 自评置信度低于该值的分录，科目走兜底替换。
     * 人工在草稿页把置信度调高（= 人工确认）后即不再替换。
     */
    private Double lowConfidenceThreshold = 0.6;

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

    /**
     * GL_VOUCHER voucher-group FNumber (凭证字). Demo account set has "记" (PRE001); the
     * rule engine always creates plain journal vouchers. Calibrated pending REAL test.
     */
    private String glVoucherGroupNumber = "PRE001";

    /**
     * GL_VOUCHER account-book FNumber (FAccountBookID, MustInput=1 per the 2026-09-11
     * QueryBusinessInfo snapshot). The production account set is org 400 (雪云); per-org
     * books need REAL-mode calibration, so the rule-engine builder uses this single value
     * until the per-org mapping lands.
     */
    private String glAcctbookNumber = "400";

    /**
     * 制证落点（2026-09-21 方案 B）：{@code GL} = 总账凭证 GL_VOUCHER（默认）；
     * {@code BILL} = 出纳收付款单 AP_PAYBILL/AR_RECEIVEBILL。
     *
     * <p>切到 GL 的原因：真实账套境内主体均未启用「出纳管理」模块（FIX-007 实测），
     * 收付款单保存一律被拒；总账模块已开通且有历史凭证（账簿 400）。出纳模块启用后
     * 可用 {@code KINGDEE_VOUCHER_TARGET=BILL} 切回，两条链路共用凭据/状态机/规则引擎。</p>
     */
    private String voucherTarget = "GL";

    /**
     * GL_VOUCHER 弹性域槽位：银行账号维度（账套维度类型 ZDY0001）落位。
     *
     * <p>2026-09-21 真实账套实测：报文形态必须两层——
     * {@code "FDetailID": {"FDETAILID__FF100002": {"FNumber": "<CN_BANKACNT 账号>"}}}，
     * 内层键是带前缀的完整字段名（裸槽位名 FF100002 无效，数组形态会报类型转换异常）。
     * 槽位归属：FF100002=银行账号 / FFLEX4=供应商 / FFLEX5=部门 / FFLEX6=客户 /
     * FFLEX7=员工 / FFLEX8=物料 / FFLEX9=费用项目 / FFLEX10=资产类别 / FFLEX11=组织机构 /
     * FFLEX12=物料分组 / FFLEX13=客户分组。</p>
     */
    private String glBankDimensionSlot = "FF100002";

    /** 科目目录（BD_Account）缓存有效期（秒）：科目校验与维度需求判定用，避免每次推送都拉全表。 */
    private Integer accountCatalogTtlSeconds = 600;

    public String getVoucherTarget() {
        return voucherTarget;
    }

    public void setVoucherTarget(String voucherTarget) {
        this.voucherTarget = voucherTarget;
    }

    public String getGlBankDimensionSlot() {
        return glBankDimensionSlot;
    }

    public void setGlBankDimensionSlot(String glBankDimensionSlot) {
        this.glBankDimensionSlot = glBankDimensionSlot;
    }

    public Integer getAccountCatalogTtlSeconds() {
        return accountCatalogTtlSeconds;
    }

    public void setAccountCatalogTtlSeconds(Integer accountCatalogTtlSeconds) {
        this.accountCatalogTtlSeconds = accountCatalogTtlSeconds;
    }

    /** 落点是否为总账凭证（GL）。 */
    public boolean isGlTarget() {
        return voucherTarget == null || "GL".equalsIgnoreCase(voucherTarget.trim());
    }

    public String getGlVoucherGroupNumber() {
        return glVoucherGroupNumber;
    }

    public void setGlVoucherGroupNumber(String glVoucherGroupNumber) {
        this.glVoucherGroupNumber = glVoucherGroupNumber;
    }

    public String getGlAcctbookNumber() {
        return glAcctbookNumber;
    }

    public void setGlAcctbookNumber(String glAcctbookNumber) {
        this.glAcctbookNumber = glAcctbookNumber;
    }

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

    public String getFallbackAccount() {
        return fallbackAccount;
    }

    public void setFallbackAccount(String fallbackAccount) {
        this.fallbackAccount = fallbackAccount;
    }

    public Double getLowConfidenceThreshold() {
        return lowConfidenceThreshold;
    }

    public void setLowConfidenceThreshold(Double lowConfidenceThreshold) {
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }
}
