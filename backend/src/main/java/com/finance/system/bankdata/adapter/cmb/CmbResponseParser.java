package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.bankdata.adapter.BankPageTotals;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the decrypted, signature-verified CMB response JSON into typed rows.
 *
 * <p>Wire envelope (1.2返回报文): {@code response.head{funcode,userid,rspid,resultcode,resultmsg} +
 * response.body}. {@code resultcode=SUC0000} means the bank accepted the request; business
 * failures surface per-record (NTQADINF {@code errcod}) or per-page (trsQryByBreakPoint head).</p>
 */
public final class CmbResponseParser {

    public static final String SUCCESS_CODE = "SUC0000";

    private CmbResponseParser() {
    }

    /** Parsed envelope: head fields plus the raw body object (may be empty). */
    public record Envelope(String funcode, String reqid, String rspid, String resultcode,
                           String resultmsg, JsonObject body) {
        public boolean succeeded() {
            return resultcode != null && SUCCESS_CODE.equals(resultcode.trim());
        }
    }

    /** NTQADINF per-account balance row (ntqadinfz element). */
    public record BalanceRow(String ccynbr, String bbknbr, String accnbr, String accnam,
                             String onlblv, String hldblv, String avlblv, String lmtovr,
                             String stscod, String errcod, String errtxt,
                             String accblv, String accitm, String relnbr,
                             String opndat, String inttyp, String dpstxt) {
    }

    /**
     * trsQryByBreakPoint per-transaction row (Z2 element) — all 30 fields of doc 10, verbatim.
     * {@code transAmount} keeps the bank's sign (D 借方 negative, C 贷方 positive).
     */
    public record StatementRow(String transDate, String transSequenceIdn, String transTime,
                               String valueDate, String loanCode, String transAmount,
                               String currencyNbr, String textCode, String billNumber,
                               String remarkTextClt, String reversalFlag, String acctOnlineBal,
                               String extendedRemark, String ctpAcctNbr, String ctpAcctName,
                               String ctpBankName, String ctpBankAddress, String fatOrSonAccount,
                               String fatOrSonCompanyName, String fatOrSonBankName,
                               String fatOrSonBankAddress, String infoFlag, String businessName,
                               String businessText, String requestNbr, String yurRef,
                               String virtualNbr, String mchOrderNbr, String transCardNbr,
                               String reserve) {
    }

    /** trsQryByBreakPoint page result (Z1 continuation control + page totals + Z2 rows). */
    public record StatementPage(String ctnFlag, String queryAcctNbr, BankPageTotals pageTotals,
                                List<CmbStatementQuery.CmbStatementBreakPoint> breakPoints,
                                List<StatementRow> rows) {
    }

    public static Envelope parseEnvelope(String decryptedJson) {
        JsonObject root;
        try {
            root = JsonParser.parseString(decryptedJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB response is not valid JSON", e);
        }
        JsonElement responseElement = root.get("response");
        if (responseElement == null || !responseElement.isJsonObject()) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB response is missing the response envelope");
        }
        JsonObject response = responseElement.getAsJsonObject();
        JsonObject head = objectOrEmpty(response.get("head"));
        JsonObject body = objectOrEmpty(response.get("body"));
        return new Envelope(
                text(head.get("funcode")),
                text(head.get("reqid")),
                text(head.get("rspid")),
                text(head.get("resultcode")),
                text(head.get("resultmsg")),
                body);
    }

    /** NTQADINF body {@code ntqadinfz} → per-account rows. */
    public static List<BalanceRow> parseBalanceRows(Envelope envelope) {
        JsonArray array = arrayOrEmpty(envelope.body(), "ntqadinfz");
        List<BalanceRow> rows = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject row = element.getAsJsonObject();
            rows.add(new BalanceRow(
                    text(row.get("ccynbr")), text(row.get("bbknbr")), text(row.get("accnbr")),
                    text(row.get("accnam")), text(row.get("onlblv")), text(row.get("hldblv")),
                    text(row.get("avlblv")), text(row.get("lmtovr")), text(row.get("stscod")),
                    text(row.get("errcod")), text(row.get("errtxt")),
                    text(row.get("accblv")), text(row.get("accitm")), text(row.get("relnbr")),
                    text(row.get("opndat")), text(row.get("inttyp")), text(row.get("dpstxt"))));
        }
        return List.copyOf(rows);
    }

    /** trsQryByBreakPoint body → page result (Y1 break points, Z1 control, Z2 rows). */
    public static StatementPage parseStatementPage(Envelope envelope) {
        List<CmbStatementQuery.CmbStatementBreakPoint> breakPoints = new ArrayList<>();
        JsonArray y1 = arrayOrEmpty(envelope.body(), "TRANSQUERYBYBREAKPOINT_Y1");
        for (JsonElement element : y1) {
            JsonObject row = element.getAsJsonObject();
            breakPoints.add(new CmbStatementQuery.CmbStatementBreakPoint(
                    text(row.get("acctNbr")), text(row.get("transDate")),
                    text(row.get("expectNextSequence"))));
        }

        String ctnFlag = null;
        String queryAcctNbr = null;
        BankPageTotals pageTotals = null;
        JsonArray z1 = arrayOrEmpty(envelope.body(), "TRANSQUERYBYBREAKPOINT_Z1");
        if (!z1.isEmpty()) {
            JsonObject control = z1.get(0).getAsJsonObject();
            ctnFlag = text(control.get("ctnFlag"));
            queryAcctNbr = text(control.get("queryAcctNbr"));
            // The bank's own debit/credit totals for this page (Z1). Kept verbatim —
            // signed amounts, string-rendered numbers — so the executor's window sum is
            // reconcilable against the bank's own figures rather than our recount.
            pageTotals = new BankPageTotals(
                    decimal(control.get("debitAmount")), whole(control.get("debitNums")),
                    decimal(control.get("creditAmount")), whole(control.get("creditNums")));
        }

        List<StatementRow> rows = new ArrayList<>();
        JsonArray z2 = arrayOrEmpty(envelope.body(), "TRANSQUERYBYBREAKPOINT_Z2");
        for (JsonElement element : z2) {
            JsonObject row = element.getAsJsonObject();
            rows.add(new StatementRow(
                    text(row.get("transDate")), text(row.get("transSequenceIdn")),
                    text(row.get("transTime")), text(row.get("valueDate")),
                    text(row.get("loanCode")), text(row.get("transAmount")),
                    text(row.get("currencyNbr")), text(row.get("textCode")),
                    text(row.get("billNumber")), text(row.get("remarkTextClt")),
                    text(row.get("reversalFlag")), text(row.get("acctOnlineBal")),
                    text(row.get("extendedRemark")), text(row.get("ctpAcctNbr")),
                    text(row.get("ctpAcctName")), text(row.get("ctpBankName")),
                    text(row.get("ctpBankAddress")), text(row.get("fatOrSonAccount")),
                    text(row.get("fatOrSonCompanyName")), text(row.get("fatOrSonBankName")),
                    text(row.get("fatOrSonBankAddress")), text(row.get("infoFlag")),
                    text(row.get("businessName")), text(row.get("businessText")),
                    text(row.get("requestNbr")), text(row.get("yurRef")),
                    text(row.get("virtualNbr")), text(row.get("mchOrderNbr")),
                    text(row.get("transCardNbr")), text(row.get("reserve"))));
        }
        return new StatementPage(ctnFlag, queryAcctNbr, pageTotals, List.copyOf(breakPoints), List.copyOf(rows));
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arrayOrEmpty(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Bank numeric string → BigDecimal; blank or non-numeric stays null rather than zero. */
    private static BigDecimal decimal(JsonElement element) {
        String value = text(element);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Bank count string → Long; same rule as {@link #decimal}. */
    private static Long whole(JsonElement element) {
        BigDecimal value = decimal(element);
        return value == null ? null : Long.valueOf(value.longValue());
    }
}
