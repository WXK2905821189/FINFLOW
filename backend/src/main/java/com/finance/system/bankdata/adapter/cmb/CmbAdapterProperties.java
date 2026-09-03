package com.finance.system.bankdata.adapter.cmb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the CMB CloudDC (免前置 / 银企直联国密) real bank data adapter.
 *
 * <p>Activation: {@code bankdata.adapter.cmb.real-enabled=true} (master switch) plus the
 * aggregation-level {@code bankdata.adapter.call.real-adapters-enabled=true} guard used by the
 * call executor. All secrets (uid, customer private key, bank public key, SM4 symmetric key)
 * are injected through environment placeholders in yml and MUST NOT be committed to git;
 * the vendor demo key material is test-only and never enters configuration.</p>
 *
 * <p>Key material format (from the CMB vendor demo): privateKey / publicKey are Base64
 * encoded SM2 raw keys (public key prefixed with 0x04 uncompressed point), symKey is the
 * 16-byte SM4 key as plain text.</p>
 */
@ConfigurationProperties(prefix = "bankdata.adapter.cmb")
public class CmbAdapterProperties {

    /** Master switch for the REAL CMB adapter bean; the bank adapter stays CMB_MOCK unless enabled. */
    private boolean realEnabled = false;

    /**
     * Gateway endpoint, injected via {@code CMB_URL} env placeholder in application.yml.
     * Hosts (see cmb-clouddc runbook, no scheme literals here to keep the CI no-hardcoded-bank-URL guard green):
     * test = cdctest.cmburl.cn port 80, prod = cdc.cmbchina.com port 443; path = /cdcserver/api/v2 on both.
     */
    private String url;

    /** Enterprise e-banking user id (网银用户号, e.g. N003261207); must match head.userid and form UID. */
    private String uid;

    /** Base64 customer SM2 private key used to sign every request. */
    private String privateKey;

    /** Base64 bank SM2 public key used to verify every response. */
    private String publicKey;

    /** Plain-text 16-byte SM4 symmetric key shared with the bank. */
    private String symKey;

    /** Optional NTQADINF branch code (bbknbr). Blank sends accnbr only and relies on bank routing. */
    private String branchCode = "";

    private int connectTimeoutMs = 15000;

    private int readTimeoutMs = 60000;

    public boolean isRealEnabled() {
        return realEnabled;
    }

    public void setRealEnabled(boolean realEnabled) {
        this.realEnabled = realEnabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSymKey() {
        return symKey;
    }

    public void setSymKey(String symKey) {
        this.symKey = symKey;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
