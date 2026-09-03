package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;
import org.w3c.dom.Element;

/**
 * Builds and unwraps the CITIC outer transport envelope (action DLGECOMM) that carries
 * the GBK Base64-encoded business XML, mirroring the vendor demo's sendAction contract.
 */
public final class CiticEnvelopeCodec {

    private static final String OUTER_ACTION = "DLGECOMM";
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"GBK\"?>";

    private CiticEnvelopeCodec() {
    }

    /**
     * Builds the outer request XML. The business payload is GBK-Base64 encoded per
     * the vendor contract; the outer structure is fixed and must not be altered.
     *
     * @param userName login name varchar(30)
     * @param businessXml inner business-layer XML (DLBALQRY / DLTRNALL / ...)
     * @param cashFlag 0 = single entity, 1 = group
     * @param clientId customer serial number varchar(30); idempotency / trace key
     */
    public static String buildOuterRequest(String userName, String businessXml, String cashFlag, String clientId) {
        if (businessXml == null || businessXml.isBlank()) {
            throw new BusinessException(400, "CITIC outer envelope requires a business payload");
        }
        return new StringBuilder(XML_HEADER).append("<stream>")
                .append("<action>").append(OUTER_ACTION).append("</action>")
                .append("<userName>").append(CiticRequestXml.escape(userName)).append("</userName>")
                .append("<requestContent>").append(CiticBankDataCodec.encodeGbkBase64(businessXml)).append("</requestContent>")
                .append("<CASHFLAG>").append(cashFlag == null || cashFlag.isBlank() ? "0" : CiticRequestXml.escape(cashFlag)).append("</CASHFLAG>")
                .append("<clientID>").append(CiticRequestXml.escape(clientId)).append("</clientID>")
                .append("</stream>").toString();
    }

    /**
     * Parses the outer response and decodes responseContent (GBK Base64) into the inner
     * business XML. Transport-level status remains available for two-level checking.
     */
    public static CiticEnvelopeResponse parseOuterResponse(String outerResponseXml) {
        Element stream = CiticResponseXml.parseRoot(outerResponseXml);
        String status = CiticResponseXml.firstChildText(stream, "status");
        String statusText = CiticResponseXml.firstChildText(stream, "statusText");
        String encoded = CiticResponseXml.firstChildText(stream, "responseContent");
        if (encoded == null) {
            return new CiticEnvelopeResponse(status, statusText, null);
        }
        String businessXml = CiticBankDataCodec.decodeGbkBase64(encoded);
        return new CiticEnvelopeResponse(status, statusText, businessXml);
    }

    /**
     * Client serial number bounded to the vendor varchar(30) limit. Combines the sync
     * request id with the page so every transport call carries a distinct trace key.
     */
    public static String clientId(String requestId, int page) {
        String base = requestId == null || requestId.isBlank() ? "finflow" : requestId.trim();
        String bounded = base.length() > 20 ? base.substring(base.length() - 20) : base;
        return bounded + "-P" + Math.max(1, page);
    }
}
