package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.BalanceRow;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.Envelope;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementPage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 contract test against a fake CMB gateway over real HTTP: canonical sort → SM2 sign →
 * SM4 encrypt → form POST (UID/ALG/DATA/FUNCODE) → gateway decrypt/verify → signed+encrypted
 * response → client decrypt/verify → parse. All keys are ephemeral per test instance.
 */
class CmbGatewayContractTest {

    private static final String REQ_ID = "20260903120000001FINFLOW01";
    private static final String BALANCE_RESPONSE = "{\"response\":{\"head\":{\"funcode\":\"NTQADINF\","
            + "\"resultcode\":\"SUC0000\",\"resultmsg\":\"\"},\"body\":{\"ntqadinfz\":[{"
            + "\"accnbr\":\"769900000010370\",\"avlblv\":\"12345.67\",\"errcod\":\"SUC0000\","
            + "\"stscod\":\"A\"}]}}}";
    private static final String STATEMENT_RESPONSE = "{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\","
            + "\"resultcode\":\"SUC0000\",\"resultmsg\":\"\"},\"body\":{"
            + "\"TRANSQUERYBYBREAKPOINT_Y1\":[{\"acctNbr\":\"769900000010370\",\"transDate\":\"20260901\","
            + "\"expectNextSequence\":\"2\"}],"
            + "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"ctnFlag\":\"N\",\"queryAcctNbr\":\"\"}],"
            + "\"TRANSQUERYBYBREAKPOINT_Z2\":[{\"transDate\":\"20260901\",\"transTime\":\"101530\","
            + "\"transSequenceIdn\":\"C09468U00012KWZ\",\"loanCode\":\"C\",\"transAmount\":\"100.50\","
            + "\"ctpAcctNbr\":\"957151020441242810\",\"ctpAcctName\":\"对手方公司\","
            + "\"businessText\":\"网银摘要\"}]}}}";

    private FakeCmbServer bank;
    private CmbHttpGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        bank = new FakeCmbServer();
        gateway = new CmbHttpGateway(bank.clientProperties());
    }

    @AfterEach
    void tearDown() {
        bank.close();
    }

    @Test
    void fullBalanceChainSortSignEncryptPostVerifyParse() {
        bank.respondBalance(BALANCE_RESPONSE);
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount("769900000010370", "69", null)));
        JsonObject document = CmbRequestBuilder.balanceDocument(FakeCmbServer.TEST_UID, REQ_ID, query);

        String response = gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document);

        Envelope envelope = CmbResponseParser.parseEnvelope(response);
        assertTrue(envelope.succeeded());
        List<BalanceRow> rows = CmbResponseParser.parseBalanceRows(envelope);
        assertEquals(1, rows.size());
        assertEquals("769900000010370", rows.get(0).accnbr());
        assertEquals("12345.67", rows.get(0).avlblv());

        FakeCmbServer.CapturedRequest captured = bank.requests().get(0);
        assertEquals("NTQADINF", captured.funcode);
        assertEquals(FakeCmbServer.TEST_UID, captured.uid);
        assertEquals("SM", captured.alg);
        assertTrue(captured.data != null && !captured.data.isEmpty());
        // The gateway decrypted + verified the client signature over the canonical source.
        assertTrue(captured.signatureValid);
        JsonObject body = captured.decryptedRequest.getAsJsonObject("request").getAsJsonObject("body");
        JsonObject account = body.getAsJsonArray("ntqadinfx").get(0).getAsJsonObject();
        assertEquals("769900000010370", account.get("accnbr").getAsString());
        assertEquals("69", account.get("bbknbr").getAsString());
    }

    @Test
    void fullStatementChainCarriesX1ConditionAndY1Continuation() {
        bank.respondStatement(STATEMENT_RESPONSE);
        CmbStatementQuery query = CmbStatementQuery.firstPage("769900000010370", "20260901", "20260902");
        JsonObject document = CmbRequestBuilder.statementDocument(FakeCmbServer.TEST_UID, REQ_ID, query);

        String response = gateway.exchange(CmbRequestBuilder.FUNCODE_STATEMENT, document);

        Envelope envelope = CmbResponseParser.parseEnvelope(response);
        assertTrue(envelope.succeeded());
        StatementPage page = CmbResponseParser.parseStatementPage(envelope);
        assertEquals("N", page.ctnFlag());
        assertEquals(1, page.rows().size());
        assertEquals("C09468U00012KWZ", page.rows().get(0).transSequenceIdn());
        assertEquals(1, page.breakPoints().size());

        FakeCmbServer.CapturedRequest captured = bank.requests().get(0);
        assertEquals("trsQryByBreakPoint", captured.funcode);
        assertTrue(captured.signatureValid);
        JsonObject condition = captured.decryptedRequest.getAsJsonObject("request")
                .getAsJsonObject("body").getAsJsonArray("TRANSQUERYBYBREAKPOINT_X1")
                .get(0).getAsJsonObject();
        assertEquals("769900000010370", condition.get("cardNbr").getAsString());
        assertEquals("20260901", condition.get("beginDate").getAsString());
    }

    @Test
    void surfacesGatewayLayerErrorAsGatewayKind() {
        bank.respondGatewayError("网络层无法连接后台服务");
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount("A1", null, null)));
        JsonObject document = CmbRequestBuilder.balanceDocument(FakeCmbServer.TEST_UID, REQ_ID, query);

        CmbCallException error = assertThrows(CmbCallException.class,
                () -> gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document));
        assertEquals(CmbCallException.Kind.GATEWAY, error.kind());
        assertTrue(error.getMessage().startsWith("CDCServer:"));
    }

    @Test
    void surfacesHttpFailureAsTransportKind() {
        bank.respondHttpError(503, "Service Unavailable");
        JsonObject document = CmbRequestBuilder.balanceDocument(FakeCmbServer.TEST_UID, REQ_ID,
                new CmbBalanceQuery(List.of(new CmbBalanceQuery.CmbBalanceAccount("A1", null, null))));

        CmbCallException error = assertThrows(CmbCallException.class,
                () -> gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document));
        assertEquals(CmbCallException.Kind.TRANSPORT, error.kind());
        assertTrue(error.getMessage().contains("503"));
    }

    @Test
    void rejectsResponseSignedByWrongBankKey() {
        bank.respondBalance(BALANCE_RESPONSE);
        bank.signResponsesWithWrongKey();
        JsonObject document = CmbRequestBuilder.balanceDocument(FakeCmbServer.TEST_UID, REQ_ID,
                new CmbBalanceQuery(List.of(new CmbBalanceQuery.CmbBalanceAccount("A1", null, null))));

        CmbCallException error = assertThrows(CmbCallException.class,
                () -> gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document));
        assertEquals(CmbCallException.Kind.SECURITY, error.kind());
    }

    @Test
    void rejectsIncompleteGatewayConfiguration() {
        CmbAdapterProperties empty = new CmbAdapterProperties();
        CmbHttpGateway unconfigured = new CmbHttpGateway(empty);
        JsonObject document = CmbRequestBuilder.balanceDocument("U1", REQ_ID,
                new CmbBalanceQuery(List.of(new CmbBalanceQuery.CmbBalanceAccount("A1", null, null))));
        CmbCallException error = assertThrows(CmbCallException.class,
                () -> unconfigured.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document));
        assertEquals(CmbCallException.Kind.PROTOCOL, error.kind());
        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void balanceDocumentSignatureIsStableAcrossKeySortingOnServerSide() {
        // Guards the cross-implementation contract: canonicalization of the client request
        // (placeholder sigdat) matches the sort the fake gateway re-derives before verifying.
        bank.respondBalance(BALANCE_RESPONSE);
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount("769900000010370", null, "10")));
        JsonObject document = CmbRequestBuilder.balanceDocument(FakeCmbServer.TEST_UID, REQ_ID, query);
        gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE, document);
        assertTrue(bank.requests().get(0).signatureValid);
        assertFalse(bank.requests().isEmpty());
    }
}
