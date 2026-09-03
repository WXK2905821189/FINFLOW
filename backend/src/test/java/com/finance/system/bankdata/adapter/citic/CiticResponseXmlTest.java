package com.finance.system.bankdata.adapter.citic;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiticResponseXmlTest {

    @Test
    void parsesStatementPageContainerAndRows() {
        String xml = "<?xml version=\"1.0\" encoding=\"GBK\"?>\n"
                + "<stream><action>DLTRNALL</action>"
                + "<status>AAAAAAA</status><statusText/>"
                + "<accountNo>8110710000000000001</accountNo><accountName>测试户</accountName>"
                + "<totalRecords>23</totalRecords><returnRecords>20</returnRecords>"
                + "<list name=\"userDataList\">"
                + "<row><tranDate>20260901</tranDate><tranTime>101530</tranTime>"
                + "<tranNo>T0000000000001</tranNo><sumTranNo>S0000000000001</sumTranNo>"
                + "<tranAmount>88.00</tranAmount><creditDebitFlag>C</creditDebitFlag>"
                + "<oppAccountNo>6222000000001</oppAccountNo><oppAccountName>收款方</oppAccountName>"
                + "<oppOpenBankName>中信银行</oppOpenBankName><abstract>货款</abstract>"
                + "<balance>1088.00</balance><oriNum>ORI-001</oriNum>"
                + "<futureField>must-be-skipped</futureField></row>"
                + "<row><tranDate>20260902</tranDate><tranTime/>"
                + "<tranNo>T0000000000002</tranNo><sumTranNo>S0000000000002</sumTranNo>"
                + "<tranAmount>12.00</tranAmount><creditDebitFlag>D</creditDebitFlag>"
                + "<oppAccountNo/><oppAccountName/><abstract>手续费</abstract>"
                + "<balance>1076.00</balance><oriNum>ORI-002</oriNum></row>"
                + "</list></stream>";

        CiticStatementPage page = CiticResponseXml.parseStatementPage(xml);

        assertEquals("AAAAAAA", page.status());
        assertEquals("8110710000000000001", page.accountNo());
        assertEquals("测试户", page.accountName());
        assertEquals(23, page.totalRecords());
        assertEquals(20, page.returnRecords());
        assertEquals(2, page.rows().size());

        CiticStatementRow first = page.rows().get(0);
        assertEquals("20260901", first.tranDate());
        assertEquals("101530", first.tranTime());
        assertEquals("T0000000000001", first.tranNo());
        assertEquals(new BigDecimal("88.00"), first.tranAmount());
        assertEquals("C", first.creditDebitFlag());
        assertEquals("收款方", first.oppAccountName());
        assertEquals("货款", first.summary());
        assertEquals("ORI-001", first.oriNum());

        CiticStatementRow second = page.rows().get(1);
        assertEquals("D", second.creditDebitFlag());
        assertNull(second.oppAccountName());
    }

    @Test
    void skipsUnknownFieldsAndKeepsTagCaseSensitivity() {
        String xml = "<stream><status>AAAAAAA</status>"
                + "<list name=\"userDataList\">"
                // vendor would never send lowercase tranNo; parser must not match it
                + "<row><trano>wrong-case</trano><tranNo>T0000000000003</tranNo>"
                + "<tranAmount>5.00</tranAmount><creditDebitFlag>C</creditDebitFlag>"
                + "<vendorNewField>ignored</vendorNewField></row>"
                + "</list></stream>";

        List<CiticStatementRow> rows = CiticResponseXml.parseStatementPage(xml).rows();

        assertEquals(1, rows.size());
        assertEquals("T0000000000003", rows.get(0).tranNo());
        assertNull(rows.get(0).sumTranNo());
        assertEquals(new BigDecimal("5.00"), rows.get(0).tranAmount());
    }

    @Test
    void parsesBalanceQueryWithAccountLevelStatus() {
        String xml = "<stream><status>AAAAAAA</status>"
                + "<list name=\"userDataList\">"
                + "<row><status>AAAAAAA</status><accountNo>8110710000000000001</accountNo>"
                + "<accountName>活期户</accountName><currencyID>01</currencyID>"
                + "<usableBalance>1000.00</usableBalance><balance>1088.00</balance>"
                + "<forzenAmt>0.00</forzenAmt></row>"
                + "<row><status>AB12345</status><statusText>账号状态异常</statusText>"
                + "<accountNo>8110710000000000009</accountNo><accountName>冻结户</accountName>"
                + "<usableBalance>0.00</usableBalance><balance>0.00</balance></row>"
                + "</list></stream>";

        CiticBalanceResult result = CiticResponseXml.parseBalanceQuery(xml);

        assertEquals("AAAAAAA", result.status());
        assertEquals(2, result.rows().size());
        CiticBalanceRow healthy = result.rows().get(0);
        assertEquals(new BigDecimal("1000.00"), healthy.usableBalance());
        assertEquals(new BigDecimal("1088.00"), healthy.balance());
        assertEquals("01", healthy.currencyId());
        assertEquals("AB12345", result.rows().get(1).status());
        assertTrue(result.rows().get(1).statusText().contains("异常"));
    }

    @Test
    void toleratesEmptyUserDataList() {
        String xml = "<stream><status>AAAAAAA</status><returnRecords>0</returnRecords>"
                + "<list name=\"userDataList\"/></stream>";

        CiticStatementPage page = CiticResponseXml.parseStatementPage(xml);

        assertEquals(0, page.rows().size());
        assertEquals(0, page.returnRecords());
        assertNull(page.accountNo());
    }

    @Test
    void rejectsMalformedXmlWithoutParsingPartials() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> CiticResponseXml.parseStatementPage("<stream><status>AAAAAAA</status>"));
    }
}
