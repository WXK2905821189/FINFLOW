package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiticRequestXmlTest {

    @Test
    void buildsBalanceQueryWithExactActionAndAccountRows() {
        String xml = CiticRequestXml.buildBalanceQuery("finflow-user",
                new CiticBalanceQuery(List.of("8110710000000000001", "8110710000000000002")));

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GBK\"?>"));
        assertTrue(xml.contains("<action>DLBALQRY</action>"));
        assertTrue(xml.contains("<list name=\"userDataList\">"));
        assertTrue(xml.contains("<accountNo>8110710000000000001</accountNo>"));
        assertTrue(xml.contains("<accountNo>8110710000000000002</accountNo>"));
        // User-controlled value must be XML-escaped.
        assertTrue(xml.contains("<userName>finflow-user</userName>"));
    }

    @Test
    void escapesUserControlledValues() {
        String xml = CiticRequestXml.buildBalanceQuery("a&b<c>\"d'", new CiticBalanceQuery(List.of("81107")));

        assertTrue(xml.contains("<userName>a&amp;b&lt;c&gt;&quot;d&apos;</userName>"));
        assertTrue(xml.contains("<accountNo>81107</accountNo>"));
        assertFalse(xml.contains("a&b<c>"));
    }

    @Test
    void buildsStatementQueryWithWindowPagingAndControlFlagTwo() {
        CiticStatementQuery query = new CiticStatementQuery("8110710000000000001",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), 20, 21, 2);
        String xml = CiticRequestXml.buildStatementQuery("finflow-user", query);

        assertTrue(xml.contains("<action>DLTRNALL</action>"));
        assertTrue(xml.contains("<accountNo>8110710000000000001</accountNo>"));
        assertTrue(xml.contains("<startDate>20260901</startDate>"));
        assertTrue(xml.contains("<endDate>20260902</endDate>"));
        assertTrue(xml.contains("<pageNumber>20</pageNumber>"));
        assertTrue(xml.contains("<startRecord>21</startRecord>"));
        assertTrue(xml.contains("<controlFlag>2</controlFlag>"));
    }

    @Test
    void rejectsWindowsLongerThanNinetyTwoDays() {
        BusinessException exception = assertThrows(BusinessException.class, () -> new CiticStatementQuery(
                "8110710000000000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1), 20, 1, 2));
        assertEquals(400, exception.getCode());
    }

    @Test
    void rejectsPageNumberAboveTwenty() {
        assertThrows(BusinessException.class, () -> new CiticStatementQuery(
                "8110710000000000001", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), 21, 1, 2));
    }

    @Test
    void rejectsEmptyOrNullAccountLists() {
        assertThrows(BusinessException.class, () -> new CiticBalanceQuery(List.of()));
        assertThrows(BusinessException.class, () -> new CiticBalanceQuery(null));
    }
}
