package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 request-wire-shape tests: head fields, signature placeholder, NTQADINF ntqadinfx array
 * and trsQryByBreakPoint X1/Y1 construction (first page vs continuation).
 */
class CmbRequestBuilderTest {

    private static final String UID = "N003261207";
    private static final String REQ_ID = "20260903120000001FINFLOW01";

    @Test
    void balanceDocumentCarriesHeadAndSignaturePlaceholder() {
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount("755947919810515", "69", null),
                new CmbBalanceQuery.CmbBalanceAccount("769900000010370", null, "10")));
        JsonObject document = CmbRequestBuilder.balanceDocument(UID, REQ_ID, query);

        JsonObject head = document.getAsJsonObject("request").getAsJsonObject("head");
        assertEquals("NTQADINF", head.get("funcode").getAsString());
        assertEquals(UID, head.get("userid").getAsString());
        assertEquals(REQ_ID, head.get("reqid").getAsString());

        JsonObject signature = document.getAsJsonObject("signature");
        assertEquals("__signature_sigdat__", signature.get("sigdat").getAsString());
        assertTrue(signature.get("sigtim").getAsString().matches("^\\d{14}$"));

        JsonArray accounts = document.getAsJsonObject("request").getAsJsonObject("body")
                .getAsJsonArray("ntqadinfx");
        assertEquals(2, accounts.size());
        JsonObject first = accounts.get(0).getAsJsonObject();
        assertEquals("755947919810515", first.get("accnbr").getAsString());
        assertEquals("69", first.get("bbknbr").getAsString());
        assertFalse(first.has("ccynbr"));
        JsonObject second = accounts.get(1).getAsJsonObject();
        assertEquals("10", second.get("ccynbr").getAsString());
        assertFalse(second.has("bbknbr"));
    }

    @Test
    void statementFirstPageOmitsContinuationFields() {
        CmbStatementQuery query = CmbStatementQuery.firstPage("755947919810515", "20260901", "20260902");
        JsonObject document = CmbRequestBuilder.statementDocument(UID, REQ_ID, query);
        JsonObject body = document.getAsJsonObject("request").getAsJsonObject("body");

        assertEquals("trsQryByBreakPoint",
                document.getAsJsonObject("request").getAsJsonObject("head").get("funcode").getAsString());

        JsonArray x1 = body.getAsJsonArray("TRANSQUERYBYBREAKPOINT_X1");
        assertEquals(1, x1.size());
        JsonObject condition = x1.get(0).getAsJsonObject();
        assertEquals("755947919810515", condition.get("cardNbr").getAsString());
        assertEquals("20260901", condition.get("beginDate").getAsString());
        assertEquals("20260902", condition.get("endDate").getAsString());
        assertEquals("1", condition.get("transactionSequence").getAsString());
        assertFalse(condition.has("queryAcctNbr"));
        assertFalse(condition.has("currencyCode"));
        assertFalse(body.has("TRANSQUERYBYBREAKPOINT_Y1"));
    }

    @Test
    void statementContinuationEchoesQueryAccountAndBreakPoints() {
        CmbStatementQuery query = new CmbStatementQuery("755947919810515", "20260901", "20260902", "1",
                null, "755947919880029", null, null,
                List.of(new CmbStatementQuery.CmbStatementBreakPoint("755947919880003", "20260901", "1"),
                        new CmbStatementQuery.CmbStatementBreakPoint("755947919880029", "20260911", "101")));
        JsonObject document = CmbRequestBuilder.statementDocument(UID, REQ_ID, query);
        JsonObject body = document.getAsJsonObject("request").getAsJsonObject("body");

        JsonObject condition = body.getAsJsonArray("TRANSQUERYBYBREAKPOINT_X1").get(0).getAsJsonObject();
        assertEquals("755947919880029", condition.get("queryAcctNbr").getAsString());

        JsonArray y1 = body.getAsJsonArray("TRANSQUERYBYBREAKPOINT_Y1");
        assertEquals(2, y1.size());
        assertEquals("755947919880003", y1.get(0).getAsJsonObject().get("acctNbr").getAsString());
        assertEquals("101", y1.get(1).getAsJsonObject().get("expectNextSequence").getAsString());
    }

    @Test
    void blankOptionalStatementFieldsAreOmitted() {
        CmbStatementQuery query = new CmbStatementQuery("A1", "20260901", "20260902", "1",
                "  ", "   ", null, null, List.of());
        JsonObject document = CmbRequestBuilder.statementDocument(UID, REQ_ID, query);
        JsonObject condition = document.getAsJsonObject("request").getAsJsonObject("body")
                .getAsJsonArray("TRANSQUERYBYBREAKPOINT_X1").get(0).getAsJsonObject();
        assertFalse(condition.has("currencyCode"));
        assertFalse(condition.has("queryAcctNbr"));
        assertFalse(condition.has("reserve"));
        assertNull(condition.get("reserve"));
    }

    @Test
    void signatureSitsAsSiblingOfRequest() {
        JsonObject document = CmbRequestBuilder.balanceDocument(UID, REQ_ID,
                new CmbBalanceQuery(List.of(new CmbBalanceQuery.CmbBalanceAccount("A1", null, null))));
        assertTrue(document.has("request"));
        assertTrue(document.has("signature"));
        assertFalse(document.has("response"));
        assertEquals(2, document.size());
    }
}
