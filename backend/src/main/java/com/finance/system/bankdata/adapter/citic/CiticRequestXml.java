package com.finance.system.bankdata.adapter.citic;

/**
 * Assembles CITIC business-layer XML requests (GBK declared, tags case-sensitive
 * exactly as defined by the vendor). Pure string building; no network or SDK access.
 */
public final class CiticRequestXml {

    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"GBK\"?>";

    private CiticRequestXml() {
    }

    /**
     * DLBALQRY business XML.
     *
     * @param userName vendor login name varchar(30)
     * @param query    balance query model
     */
    public static String buildBalanceQuery(String userName, CiticBalanceQuery query) {
        StringBuilder xml = new StringBuilder(XML_HEADER).append("<stream>")
                .append("<action>DLBALQRY</action>")
                .append("<userName>").append(escape(userName)).append("</userName>")
                .append("<list name=\"userDataList\">");
        for (String accountNo : query.accountNos()) {
            xml.append("<row><accountNo>").append(escape(accountNo)).append("</accountNo></row>");
        }
        return xml.append("</list></stream>").toString();
    }

    /**
     * DLTRNALL business XML. controlFlag=2 requests oriNum (raw serial number).
     *
     * <p>lowAmount/upAmount are documented as optional in the vendor interface spec, but the
     * TSEA test gateway rejects requests without them (ED01016 "输入的最小金额为空",
     * verified 2026-09-15). FINFLOW never filters statements by amount, so the widest
     * representable decimal(15,2) range is sent: 0.00 to 9999999999999.99.</p>
     *
     * @param userName vendor login name varchar(30)
     * @param query    statement query model
     */
    public static String buildStatementQuery(String userName, CiticStatementQuery query) {
        return new StringBuilder(XML_HEADER).append("<stream>")
                .append("<action>DLTRNALL</action>")
                .append("<userName>").append(escape(userName)).append("</userName>")
                .append("<accountNo>").append(escape(query.accountNo())).append("</accountNo>")
                .append("<lowAmount>0.00</lowAmount>")
                .append("<upAmount>9999999999999.99</upAmount>")
                .append("<startDate>").append(query.startDateText()).append("</startDate>")
                .append("<endDate>").append(query.endDateText()).append("</endDate>")
                .append("<pageNumber>").append(query.pageNumber()).append("</pageNumber>")
                .append("<startRecord>").append(query.startRecord()).append("</startRecord>")
                .append("<controlFlag>").append(query.controlFlag()).append("</controlFlag>")
                .append("</stream>").toString();
    }

    /** Minimal XML text escaping for element content (values originate from local data). */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
