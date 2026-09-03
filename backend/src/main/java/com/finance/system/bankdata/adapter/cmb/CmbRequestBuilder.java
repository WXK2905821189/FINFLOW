package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Builds the plain-text request JSON documents for the two FINFLOW interfaces.
 *
 * <p>Wire envelope (1.1请求报文): {@code request.head{funcode,userid,reqid} + request.body + top-level
 * signature{sigtim,sigdat:"__signature_sigdat__"}}. CmbHttpGateway signs/encrypts the returned
 * document; key order here is irrelevant because the bank verifies over the ASCII-sorted source.</p>
 */
public final class CmbRequestBuilder {

    public static final String FUNCODE_BALANCE = "NTQADINF";
    public static final String FUNCODE_STATEMENT = "trsQryByBreakPoint";
    public static final String SIG_PLACEHOLDER = "__signature_sigdat__";

    private CmbRequestBuilder() {
    }

    /** NTQADINF body: {@code body.ntqadinfx = [{accnbr, bbknbr?, ccynbr?}]}, ≤30 accounts. */
    public static JsonObject balanceDocument(String uid, String reqid, CmbBalanceQuery query) {
        JsonArray accounts = new JsonArray();
        for (CmbBalanceQuery.CmbBalanceAccount account : query.accounts()) {
            JsonObject item = new JsonObject();
            item.addProperty("accnbr", account.accountNo());
            if (notBlank(account.branchCode())) {
                item.addProperty("bbknbr", account.branchCode());
            }
            if (notBlank(account.currency())) {
                item.addProperty("ccynbr", account.currency());
            }
            accounts.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("ntqadinfx", accounts);
        return document(uid, FUNCODE_BALANCE, reqid, body);
    }

    /**
     * trsQryByBreakPoint body: {@code X1} single-record query + optional {@code Y1} break-point
     * array echoed on continuation pages.
     */
    public static JsonObject statementDocument(String uid, String reqid, CmbStatementQuery query) {
        JsonObject x1 = new JsonObject();
        putIfNotBlank(x1, "cardNbr", query.cardNbr());
        putIfNotBlank(x1, "beginDate", query.beginDate());
        putIfNotBlank(x1, "endDate", query.endDate());
        putIfNotBlank(x1, "transactionSequence", query.transactionSequence());
        putIfNotBlank(x1, "currencyCode", query.currencyCode());
        putIfNotBlank(x1, "queryAcctNbr", query.queryAcctNbr());
        putIfNotBlank(x1, "reserve", query.reserve());
        putIfNotBlank(x1, "loanCode", query.loanCode());
        JsonArray x1Array = new JsonArray();
        x1Array.add(x1);

        JsonObject body = new JsonObject();
        body.add("TRANSQUERYBYBREAKPOINT_X1", x1Array);
        if (query.breakPoints() != null && !query.breakPoints().isEmpty()) {
            body.add("TRANSQUERYBYBREAKPOINT_Y1", breakPointArray(query.breakPoints()));
        }
        return document(uid, FUNCODE_STATEMENT, reqid, body);
    }

    private static JsonArray breakPointArray(List<CmbStatementQuery.CmbStatementBreakPoint> points) {
        JsonArray array = new JsonArray();
        for (CmbStatementQuery.CmbStatementBreakPoint point : points) {
            JsonObject item = new JsonObject();
            putIfNotBlank(item, "acctNbr", point.acctNbr());
            putIfNotBlank(item, "transDate", point.transDate());
            putIfNotBlank(item, "expectNextSequence", point.expectNextSequence());
            array.add(item);
        }
        return array;
    }

    private static JsonObject document(String uid, String funcode, String reqid, JsonObject body) {
        JsonObject head = new JsonObject();
        head.addProperty("funcode", funcode);
        head.addProperty("userid", uid);
        head.addProperty("reqid", reqid);

        JsonObject request = new JsonObject();
        request.add("head", head);
        request.add("body", body);

        JsonObject signature = new JsonObject();
        signature.addProperty("sigtim", CmbCryptoHelper.sigTime());
        signature.addProperty("sigdat", SIG_PLACEHOLDER);

        JsonObject root = new JsonObject();
        root.add("request", request);
        root.add("signature", signature);
        return root;
    }

    private static void putIfNotBlank(JsonObject object, String key, String value) {
        if (notBlank(value)) {
            object.addProperty(key, value);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
