package com.finance.system.bankdata.adapter;

import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.dlink.CiticDlinkSdk;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealCiticBankDataAdapterTest {

    private static final String ACCOUNT_NO = "8110710000000000001";
    private static final String USER_NAME = "finflow-user";

    private BankAccountMapper bankAccountMapper;
    private RecordingSdk sdk;
    private CiticAdapterProperties properties;
    private RealCiticBankDataAdapter adapter;

    @BeforeEach
    void setUp() {
        bankAccountMapper = mock(BankAccountMapper.class);
        BankAccount account = new BankAccount();
        account.setId(10L);
        account.setCompanyId(1L);
        account.setAccountNumber(ACCOUNT_NO);
        when(bankAccountMapper.selectOne(any())).thenReturn(account);

        sdk = new RecordingSdk();
        properties = new CiticAdapterProperties();
        properties.setRealEnabled(true);
        properties.setStartRecordBase(1);
        properties.setPageSize(20);
        properties.setControlFlag(2);
        properties.getSdk().setUserName(USER_NAME);
        adapter = new RealCiticBankDataAdapter(properties, sdk, bankAccountMapper);
    }

    @Test
    void collectsBalanceAndFullStatementPageOnFirstCall() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(successStatementXml(20, 20, "C"));

        BankDataCollection collection = adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        assertEquals("AAAAAAA", collection.status());
        assertEquals(20, collection.entries().size());
        assertEquals(1, collection.balances().size());
        assertTrue(collection.hasMore());
        assertEquals("21", collection.nextCursor());

        BankDataEntry first = collection.entries().get(0);
        assertEquals("T0000000000001", first.statementNo());
        assertEquals("INCOME", first.direction());
        assertEquals("2026-09-01T10:15:30", first.transactionTime().toString());
        assertEquals("对手1", first.counterpartyName());
        assertEquals("摘要1", first.summary());
        assertEquals(10L, first.bankAccountId());

        BankDataBalanceEntry balance = collection.balances().get(0);
        assertEquals(0, new java.math.BigDecimal("1000.00").compareTo(balance.availableBalance()));

        // Page 1 must query balance first, then statements starting at record base 1.
        assertEquals("DLBALQRY", sdk.actions().get(0));
        assertEquals("DLTRNALL", sdk.actions().get(1));
        assertTrue(sdk.statements().get(1).contains("<startRecord>1</startRecord>"));
        assertTrue(sdk.statements().get(1).contains("<pageNumber>20</pageNumber>"));
        assertTrue(sdk.statements().get(1).contains("<controlFlag>2</controlFlag>"));
        assertTrue(sdk.statements().get(1).contains("<startDate>20260901</startDate>"));
    }

    @Test
    void followsCursorToNextPageWithoutBalanceQuery() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(successStatementXml(20, 20, "D"));
        adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        sdk.clear();
        sdk.respondStatement(successStatementXml(5, 5, "C"));

        BankDataCollection second = adapter.collect(context(2, "2026-09-01T00:00:00", "2026-09-02T00:00:00", "21"));

        assertEquals(5, second.entries().size());
        assertTrue(second.balances().isEmpty());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertEquals("DLTRNALL", sdk.actions().get(0));
        assertTrue(sdk.statements().get(0).contains("<startRecord>21</startRecord>"));
    }

    @Test
    void mapsDebitDirectionAndFallsBackToSumTranNo() {
        String row = "<row><tranDate>20260902</tranDate><tranTime/>"
                + "<tranNo/><sumTranNo>S0000000000099</sumTranNo>"
                + "<tranAmount>12.00</tranAmount><creditDebitFlag>D</creditDebitFlag>"
                + "<abstract>手续费</abstract></row>";
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(wrapStatement("<status>AAAAAAA</status><returnRecords>1</returnRecords>"
                + "<list name=\"userDataList\">" + row + "</list>"));

        BankDataCollection collection = adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        BankDataEntry only = collection.entries().get(0);
        assertEquals("S0000000000099", only.statementNo());
        assertEquals("EXPENSE", only.direction());
        // Missing tranTime falls back to start of day.
        assertEquals("2026-09-02T00:00", only.transactionTime().toString());
        assertEquals("手续费", only.summary());
    }

    /**
     * CITIC fills the same vendor columns as CMB under different wire names, so the mapping
     * is worth locking down: 借贷码/余额/收付方 are genuine equivalents, and the fields CITIC
     * simply does not report must stay null rather than being invented.
     */
    @Test
    void statementRowsCarryCiticFieldsOnTheSharedVendorColumns() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(successStatementXml(1, 1, "C"));

        BankDataEntry entry = adapter.collect(firstWindow()).entries().get(0);
        VendorStatementFields vendor = entry.vendor();

        assertNotNull(vendor, "a CITIC row carries vendor detail too");
        assertEquals(ACCOUNT_NO, vendor.bankAccountNo(), "container accountNo is the queried account");
        assertEquals("C", vendor.loanCode());
        assertEquals(0, new BigDecimal("1.00").compareTo(vendor.signedAmount()),
                "CITIC sends the amount unsigned; a credit stays positive");
        assertEquals(0, new BigDecimal("1.00").compareTo(entry.amount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(vendor.acctOnlineBal()));
        assertEquals("62220000001", vendor.ctpAcctNbr());
        assertEquals("中信银行深圳分行", vendor.ctpBankName());
        assertEquals("S000000000001", vendor.requestNbr(), "sumTranNo");
        assertEquals("ORI-1", vendor.yurRef(), "oriNum");
        assertNull(vendor.valueDate(), "CITIC reports no 起息日 - not invented");
        assertNull(vendor.reversalFlag(), "CITIC reports no 冲账标志 - not invented");
        assertNull(vendor.infoFlag());
    }

    @Test
    void debitRowReappliesTheNegativeSignBecauseCiticSendsUnsignedAmounts() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(successStatementXml(1, 1, "D"));

        BankDataEntry entry = adapter.collect(firstWindow()).entries().get(0);

        assertEquals("D", entry.vendor().loanCode());
        assertEquals("EXPENSE", entry.direction());
        assertEquals(0, new BigDecimal("-1.00").compareTo(entry.vendor().signedAmount()),
                "the shared column is the signed figure; CITIC's flag is what carries the sign");
        assertEquals(0, new BigDecimal("1.00").compareTo(entry.amount()),
                "the accounting amount stays an unsigned magnitude");
    }

    /** DLBALQRY's usableBalance / balance / forzenAmt are the same three figures as NTQADINF. */
    @Test
    void balanceRowMapsCiticAmountsOntoTheSharedBalanceColumns() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement(successStatementXml(1, 1, "C"));

        BankDataBalanceEntry balance = adapter.collect(firstWindow()).balances().get(0);

        assertEquals(0, new BigDecimal("1000.00").compareTo(balance.availableBalance()), "usableBalance");
        assertEquals(0, new BigDecimal("1088.00").compareTo(balance.onlineBalance()), "balance");
        assertEquals(0, new BigDecimal("0.00").compareTo(balance.frozenBalance()), "forzenAmt");
        assertNull(balance.previousDayBalance(), "CITIC reports no 上日余额 - not invented");
        assertEquals("01", balance.vendorCurrencyCode());
        assertEquals(ACCOUNT_NO, balance.bankAccountNo());
        assertEquals("活期户", balance.bankAccountName());
    }

    private BankDataSyncContext firstWindow() {
        return context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null);
    }

    @Test
    void treatsNoTransactionCodeAsEmptyPageWithBalance() {
        sdk.respondBalance(successBalanceXml());
        sdk.respondStatement("<stream><status>EEEEEEE</status><statusText>无交易</statusText>"
                + "<returnRecords>0</returnRecords><list name=\"userDataList\"/></stream>");

        BankDataCollection collection = adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        assertTrue(collection.entries().isEmpty());
        assertEquals(1, collection.balances().size());
        assertFalse(collection.hasMore());
        assertNull(collection.nextCursor());
        assertEquals("AAAAAAA", collection.status());
    }

    @Test
    void skipsAccountsWithFailedBalanceStatusButKeepsHealthyRows() {
        String xml = "<stream><status>AAAAAAA</status><list name=\"userDataList\">"
                + "<row><status>AAAAAAA</status><accountNo>8110710000000000001</accountNo>"
                + "<usableBalance>1000.00</usableBalance></row>"
                + "<row><status>AB99999</status><statusText>异常</statusText>"
                + "<accountNo>8110710000000000009</accountNo><usableBalance>0.00</usableBalance></row>"
                + "</list></stream>";
        sdk.respondBalance(xml);
        sdk.respondStatement(successStatementXml(1, 1, "C"));

        BankDataCollection collection = adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        assertEquals(1, collection.balances().size());
        assertEquals(10L, collection.balances().get(0).bankAccountId());
    }

    @Test
    void surfacesContainerLevelBalanceFailure() {
        sdk.respondBalance("<stream><status>AB50000</status><statusText>余额查询失败</statusText>"
                + "<list name=\"userDataList\"/></stream>");

        BankDataCollection collection = adapter.collect(context(1, "2026-09-01T00:00:00", "2026-09-02T00:00:00", null));

        assertTrue(collection.entries().isEmpty());
        assertTrue(collection.balances().isEmpty());
        assertEquals("AB50000", collection.bankStatusCode());
        assertFalse(collection.hasMore());
    }

    private BankDataSyncContext context(int page, String start, String end, String cursor) {
        return new BankDataSyncContext(1L, 1L, 10L, "TASK-1", "req-abc-123",
                LocalDateTime.parse(start), LocalDateTime.parse(end), page, cursor, 100, "STATEMENT");
    }

    private String successBalanceXml() {
        return "<stream><status>AAAAAAA</status><list name=\"userDataList\">"
                + "<row><status>AAAAAAA</status><accountNo>" + ACCOUNT_NO + "</accountNo>"
                + "<accountName>活期户</accountName><currencyID>01</currencyID>"
                + "<usableBalance>1000.00</usableBalance><balance>1088.00</balance>"
                + "<forzenAmt>0.00</forzenAmt></row></list></stream>";
    }

    private String successStatementXml(int rows, int returnRecords, String flag) {
        StringBuilder rowsXml = new StringBuilder();
        for (int i = 1; i <= rows; i++) {
            rowsXml.append("<row><tranDate>20260901</tranDate><tranTime>101530</tranTime>")
                    .append("<tranNo>T").append(String.format(Locale.ROOT, "%013d", i)).append("</tranNo>")
                    .append("<sumTranNo>S").append(String.format(Locale.ROOT, "%012d", i)).append("</sumTranNo>")
                    .append("<tranAmount>").append(i).append(".00</tranAmount>")
                    .append("<creditDebitFlag>").append(flag).append("</creditDebitFlag>")
                    .append("<oppAccountNo>6222000000").append(i).append("</oppAccountNo>")
                    .append("<oppAccountName>对手").append(i).append("</oppAccountName>")
                    .append("<oppOpenBankName>中信银行深圳分行</oppOpenBankName>")
                    .append("<abstract>摘要").append(i).append("</abstract>")
                    .append("<balance>1000.00</balance><oriNum>ORI-").append(i).append("</oriNum></row>");
        }
        return wrapStatement("<status>AAAAAAA</status><statusText/><accountNo>" + ACCOUNT_NO
                + "</accountNo><totalRecords>25</totalRecords><returnRecords>" + returnRecords
                + "</returnRecords><list name=\"userDataList\">" + rowsXml + "</list>");
    }

    private String wrapStatement(String body) {
        return "<stream>" + body + "</stream>";
    }

    /** Records exchange calls and serves canned inner business XML per action. */
    private static class RecordingSdk implements CiticDlinkSdk {

        private final List<String> actions = new ArrayList<>();
        private final List<String> statements = new ArrayList<>();
        private final List<String> clientIds = new ArrayList<>();
        private String balanceResponse;
        private String statementResponse;

        void respondBalance(String xml) {
            this.balanceResponse = xml;
        }

        void respondStatement(String xml) {
            this.statementResponse = xml;
        }

        void clear() {
            actions.clear();
            statements.clear();
            clientIds.clear();
        }

        List<String> actions() {
            return actions;
        }

        List<String> statements() {
            return statements;
        }

        @Override
        public String exchange(String action, String businessXml, String clientId) {
            actions.add(action);
            statements.add(businessXml);
            clientIds.add(clientId);
            return "DLBALQRY".equals(action) ? balanceResponse : statementResponse;
        }

        @Override
        public String downloadCertificate(String downloadCode, String orgCode, String certPath) {
            throw new UnsupportedOperationException("not used in adapter tests");
        }
    }
}
