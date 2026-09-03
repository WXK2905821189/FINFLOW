package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.BalanceRow;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.Envelope;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementPage;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 response-parse tests against real wire samples from the CMB interface docs
 * (NTQADINF ntqadinfz + trsQryByBreakPoint Y1/Z1/Z2).
 */
class CmbResponseParserTest {

    @Test
    void parsesSuccessfulBalanceEnvelope() {
        Envelope envelope = CmbResponseParser.parseEnvelope(balanceSample());
        assertTrue(envelope.succeeded());
        assertEquals("NTQADINF", envelope.funcode());
        assertEquals("SUC0000", envelope.resultcode());
        assertFalse(envelope.body().entrySet().isEmpty());
    }

    @Test
    void parsesBusinessErrorEnvelopeWithEmptyBody() {
        String json = "{\"response\":{\"head\":{\"funcode\":\"NTQADINF\",\"userid\":\"N000097143\","
                + "\"rspid\":\"R1\",\"resultcode\":\"FABZ002\",\"resultmsg\":\"业务条件检查失败\"},\"body\":{}}}";
        Envelope envelope = CmbResponseParser.parseEnvelope(json);
        assertFalse(envelope.succeeded());
        assertEquals("FABZ002", envelope.resultcode());
        assertEquals("业务条件检查失败", envelope.resultmsg());
    }

    @Test
    void parseBalanceRowsKeepsPerAccountErrcod() {
        Envelope envelope = CmbResponseParser.parseEnvelope(balanceSample());
        List<BalanceRow> rows = CmbResponseParser.parseBalanceRows(envelope);
        assertEquals(1, rows.size());
        BalanceRow row = rows.get(0);
        assertEquals("769900000010370", row.accnbr());
        assertEquals("0.00", row.avlblv());
        assertEquals("SUC0000", row.errcod());
        assertEquals("A", row.stscod());
        assertEquals("北京迪龙化工有限公司", row.accnam());
    }

    @Test
    void balanceRowsAreEmptyWhenBodyMissing() {
        Envelope envelope = CmbResponseParser.parseEnvelope(
                "{\"response\":{\"head\":{\"resultcode\":\"SUC0000\"},\"body\":{}}}");
        assertTrue(CmbResponseParser.parseBalanceRows(envelope).isEmpty());
    }

    @Test
    void rejectsPayloadWithoutResponseEnvelope() {
        assertThrows(CmbCallException.class,
                () -> CmbResponseParser.parseEnvelope("{\"request\":{\"head\":{}}}"));
    }

    @Test
    void parsesStatementPageControlAndRows() {
        Envelope envelope = CmbResponseParser.parseEnvelope(statementSample());
        StatementPage page = CmbResponseParser.parseStatementPage(envelope);
        assertEquals("Y", page.ctnFlag());
        assertEquals("755947919880029", page.queryAcctNbr());

        assertEquals(3, page.breakPoints().size());
        assertEquals("755947919880003", page.breakPoints().get(0).acctNbr());
        assertEquals("101", page.breakPoints().get(2).expectNextSequence());

        assertEquals(1, page.rows().size());
        StatementRow row = page.rows().get(0);
        assertEquals("C09468U00012KWZ", row.transSequenceIdn());
        assertEquals("D", row.loanCode());
        assertEquals("-40.01", row.transAmount());
        assertEquals("20220228", row.transDate());
        assertEquals("140337", row.transTime());
        assertEquals("957151020441242810", row.ctpAcctNbr());
        // Wire value is a single space; the parser normalises whitespace-only text to null.
        assertNull(row.ctpAcctName());
        assertEquals("批量代付业务报文", row.remarkTextClt());
        assertNull(row.businessText());
    }

    @Test
    void statementPageDefaultsWhenControlArraysMissing() {
        String json = "{\"response\":{\"head\":{\"resultcode\":\"SUC0000\"},"
                + "\"body\":{\"TRANSQUERYBYBREAKPOINT_Z2\":[]}}}";
        StatementPage page = CmbResponseParser.parseStatementPage(
                CmbResponseParser.parseEnvelope(json));
        assertNull(page.ctnFlag());
        assertNull(page.queryAcctNbr());
        assertTrue(page.breakPoints().isEmpty());
        assertTrue(page.rows().isEmpty());
    }

    private String balanceSample() {
        return "{\"response\":{\"body\":{\"ntqadinfz\":[{\"accblv\":\"4111.01\",\"accitm\":\"10001\","
                + "\"accnam\":\"北京迪龙化工有限公司\",\"accnbr\":\"769900000010370\",\"avlblv\":\"0.00\","
                + "\"bbknbr\":\"69\",\"ccynbr\":\"10\",\"errcod\":\"SUC0000\",\"hldblv\":\"0.00\","
                + "\"intcod\":\"S\",\"intrat\":\"0.0000000\",\"lmtovr\":\"0.00\",\"mutdat\":\"00000000\","
                + "\"onlblv\":\"0.00\",\"opndat\":\"20140519\",\"relnbr\":\"0000001256\","
                + "\"stscod\":\"A\"}]},\"head\":{\"bizcode\":\"\",\"funcode\":\"NTQADINF\","
                + "\"reqid\":\"2020090816571688392496717900D86193CDB4\",\"resultcode\":\"SUC0000\","
                + "\"resultmsg\":\"\",\"rspid\":\"20200908165717654000100180374319-LW\","
                + "\"userid\":\"N000097143\"}}}";
    }

    private String statementSample() {
        return "{\"response\":{\"body\":{"
                + "\"TRANSQUERYBYBREAKPOINT_Y1\":[{\"acctNbr\":\"755947919880003\",\"transDate\":\"20230401\","
                + "\"expectNextSequence\":\"1\"},{\"acctNbr\":\"755947919880009\",\"transDate\":\"20230401\","
                + "\"expectNextSequence\":\"1\"},{\"acctNbr\":\"755947919880029\",\"transDate\":\"20230411\","
                + "\"expectNextSequence\":\"101\"}],"
                + "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"creditAmount\":\"0\",\"creditNums\":\"0\","
                + "\"ctnFlag\":\"Y\",\"queryAcctNbr\":\"755947919880029\",\"debitAmount\":\"-40.01\","
                + "\"debitNums\":\"1\"}],"
                + "\"TRANSQUERYBYBREAKPOINT_Z2\":[{\"acctOnlineBal\":\"2000110921419.82\","
                + "\"billNumber\":\"                   \",\"ctpAcctName\":\" \","
                + "\"ctpAcctNbr\":\"957151020441242810\",\"ctpBankAddress\":\" \","
                + "\"ctpBankName\":\" \",\"currencyNbr\":\"10\",\"extendedRemark\":\" \","
                + "\"fatOrSonAccount\":\" \",\"fatOrSonBankAddress\":\" \",\"fatOrSonBankName\":\" \","
                + "\"fatOrSonCompanyName\":\" \",\"infoFlag\":\"1\",\"loanCode\":\"D\","
                + "\"remarkTextClt\":\"批量代付业务报文\",\"reserve\":\"** \",\"reversalFlag\":\"N\","
                + "\"textCode\":\"EBPP\",\"transAmount\":\"-40.01\",\"transDate\":\"20220228\","
                + "\"transSequenceIdn\":\"C09468U00012KWZ\",\"transTime\":\"140337\","
                + "\"valueDate\":\"20220228\"}]},"
                + "\"head\":{\"bizcode\":\"\",\"funcode\":\"trsQryByBreakPoint\","
                + "\"reqid\":\"20230414152738333QCDC\",\"resultcode\":\"SUC0000\",\"resultmsg\":\"\","
                + "\"rspid\":\"202302141527390530001QHWS04198QD01\",\"userid\":\"U003736239\"}}}";
    }
}
