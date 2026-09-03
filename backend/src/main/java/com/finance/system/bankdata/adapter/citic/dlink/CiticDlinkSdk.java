package com.finance.system.bankdata.adapter.citic.dlink;

/**
 * Transport boundary for the vendor-supplied CITIC DLink SDK.
 *
 * <p>Implementations own the SDK lifecycle (initialize once, optional certificate
 * download) and perform the outer DLGECOMM exchange so callers only see business XML.
 * The SDK jar is not yet on the classpath; {@link UnavailableCiticDlinkSdk} is the
 * default until the jar-in-JDK17 smoke test passes and a real implementation replaces it.</p>
 */
public interface CiticDlinkSdk {

    /**
     * Exchanges one business request with the bank.
     *
     * @param action      business action code, e.g. DLBALQRY or DLTRNALL
     * @param businessXml inner business-layer XML request
     * @param clientId    customer serial number varchar(30) for tracing
     * @return decoded inner business-layer XML response
     */
    String exchange(String action, String businessXml, String clientId);

    /**
     * Downloads the cloud certificate using the one-time bank-issued download code.
     *
     * @param downloadCode one-time certificate download code
     * @param orgCode      organization code issued by the bank
     * @param certPath     writable directory where the certificate is stored
     * @return vendor status XML text (AAAAAAA means success)
     */
    String downloadCertificate(String downloadCode, String orgCode, String certPath);
}
