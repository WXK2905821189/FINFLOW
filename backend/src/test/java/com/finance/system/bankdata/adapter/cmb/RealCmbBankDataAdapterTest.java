package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.VendorStatementFields;
import com.finance.system.bankdata.adapter.cmb.FakeCmbServer.CapturedRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RealCmbBankDataAdapter orchestration over the fake gateway: page-1 balance snapshot +
 * statement, cursor continuation (queryAcctNbr + Y1 echo), failure surfacing (head-level,
 * row-level errcod), config and scope guards.
 */
class RealCmbBankDataAdapterTest {

    private static final String ACCOUNT_NO = "769900000010370";

    private BankAccountMapper bankAccountMapper;
    private FakeCmbServer bank;
    private RealCmbBankDataAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        bank = new FakeCmbServer();
        bankAccountMapper = mock(BankAccountMapper.class);
        BankAccount account = new BankAccount();
        account.setId(10L);
        account.setCompanyId(1L);
        account.setAccountNumber(ACCOUNT_NO);
        when(bankAccountMapper.selectOne(any())).thenReturn(account);
        adapter = new RealCmbBankDataAdapter(bank.clientProperties(), bankAccountMapper);
    }

    @AfterEach
    void tearDown() {
        bank.close();
    }

    @Test
    void firstPageCollectsBalanceThenStatementEntries() {
        bank.respondBalance(balanceResponse("12345.67", "SUC0000"));
        bank.respondStatement(statementResponse("Y", "769900000010370-CUR", true));

        BankDataCollection collection = adapter.collect(context(1, null));

        assertEquals("SUC0000", collection.status());
        assertEquals(1, collection.balances().size());
        BankDataBalanceEntry balance = collection.balances().get(0);
        assertEquals(0, new BigDecimal("12345.67").compareTo(balance.availableBalance()));
        assertEquals(10L, balance.bankAccountId());
        assertEquals(0, new BigDecimal("50000.00").compareTo(balance.overdraftLimit()));
        assertEquals("S", balance.interestCode());
        assertEquals(0, new BigDecimal("0.0125000").compareTo(balance.interestRate()));
        assertEquals("20270519", balance.maturityDate());

        assertEquals(1, collection.entries().size());
        BankDataEntry entry = collection.entries().get(0);
        assertEquals("T0000000000001", entry.statementNo());
        assertEquals("INCOME", entry.direction());
        assertEquals(0, new BigDecimal("100.50").compareTo(entry.amount()));
        assertEquals("2026-09-01T10:15:30", entry.transactionTime().toString());
        assertEquals("对手方公司", entry.counterpartyName());
        assertEquals("957151020441242810", entry.counterpartyAccount());
        assertEquals("网银业务摘要", entry.summary());
        assertNull(entry.currency());
        assertEquals(10L, entry.bankAccountId());

        assertTrue(collection.hasMore());
        assertNotNull(collection.nextCursor());
        assertTrue(collection.nextCursor().contains("769900000010370-CUR"));

        List<CapturedRequest> requests = bank.requests();
        assertEquals(2, requests.size());
        assertEquals("NTQADINF", requests.get(0).funcode);
        assertTrue(requests.get(0).signatureValid);
        assertEquals(FakeCmbServer.TEST_UID,
                requests.get(0).decryptedRequest.getAsJsonObject("request")
                        .getAsJsonObject("head").get("userid").getAsString());
        assertEquals("trsQryByBreakPoint", requests.get(1).funcode);
    }

    @Test
    void secondPageResumesFromCursorWithoutBalanceQuery() {
        bank.respondBalance(balanceResponse("12345.67", "SUC0000"));
        bank.respondStatement(statementResponse("Y", "769900000010370-CUR", true));
        BankDataCollection first = adapter.collect(context(1, null));

        bank.clearRequests();
        bank.respondStatement(statementResponse("N", null, false));
        BankDataCollection second = adapter.collect(context(2, first.nextCursor()));

        assertTrue(second.balances().isEmpty());
        assertEquals(1, second.entries().size());
        BankDataEntry entry = second.entries().get(0);
        assertEquals("EXPENSE", entry.direction());
        // Bank signs debits negative; FINFLOW stores magnitude with the direction flag.
        assertEquals(0, new BigDecimal("12.00").compareTo(entry.amount()));
        assertEquals("T0000000000002", entry.statementNo());
        // Compare as objects: toString() of a midnight-second LocalDateTime drops the ":00".
        assertEquals(LocalDateTime.of(2026, 9, 1, 9, 15, 0), entry.transactionTime());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());

        List<CapturedRequest> requests = bank.requests();
        assertEquals(1, requests.size());
        assertEquals("trsQryByBreakPoint", requests.get(0).funcode);
        JsonObject condition = requests.get(0).decryptedRequest.getAsJsonObject("request")
                .getAsJsonObject("body").getAsJsonArray("TRANSQUERYBYBREAKPOINT_X1")
                .get(0).getAsJsonObject();
        assertEquals("769900000010370-CUR", condition.get("queryAcctNbr").getAsString());
        JsonObject y1 = requests.get(0).decryptedRequest.getAsJsonObject("request")
                .getAsJsonObject("body").getAsJsonArray("TRANSQUERYBYBREAKPOINT_Y1")
                .get(0).getAsJsonObject();
        assertEquals("769900000010370", y1.get("acctNbr").getAsString());
    }

    /**
     * The point of the vendor fields is that nothing is translated: the screen has to be
     * comparable to the bank's own statement export field by field. So this asserts the
     * values the fake bank put on the wire come back with the same codes, the same sign
     * convention and the same names.
     */
    @Test
    void statementRowsCarryTheBanksOwnFieldsVerbatim() {
        bank.respondBalance(balanceResponse("12345.67", "SUC0000"));
        bank.respondStatement(statementResponse("Y", "769900000010370-CUR", true));

        VendorStatementFields vendor = adapter.collect(context(1, null)).entries().get(0).vendor();

        assertNotNull(vendor, "a CMB row always carries vendor detail");
        assertEquals("769900000010370-CUR", vendor.bankAccountNo());
        assertEquals(LocalDate.of(2026, 9, 2), vendor.valueDate());
        assertEquals("C", vendor.loanCode());
        // 带符号金额与无符号记账金额并存：借方为负、贷方为正。
        assertEquals(0, new BigDecimal("100.50").compareTo(vendor.signedAmount()));
        assertEquals(0, new BigDecimal("12446.17").compareTo(vendor.acctOnlineBal()));
        assertEquals("EBPP", vendor.textCode());
        assertEquals("BILL-0001", vendor.billNumber());
        assertEquals("你方摘要-代付", vendor.remarkTextClt());
        assertEquals("N", vendor.reversalFlag());
        assertEquals("扩展摘要", vendor.extendedRemark());
        assertEquals("957151020441242810", vendor.ctpAcctNbr());
        assertEquals("招商银行深圳分行", vendor.ctpBankName());
        assertEquals("深圳市", vendor.ctpBankAddress());
        assertEquals("1", vendor.infoFlag());
        assertEquals("批量代付", vendor.businessName());
        assertEquals("网银业务摘要", vendor.businessText());
        assertEquals("RQ00000001", vendor.requestNbr());
        assertEquals("YUR-REF-001", vendor.yurRef());
        assertEquals("**", vendor.reserve());
        assertNull(vendor.fatOrSonAccount(), "blank bank fields are null, not empty strings");
        assertNull(vendor.virtualNbr());
        assertNull(vendor.mchOrderNbr());
        assertNull(vendor.transCardNbr());
    }

    @Test
    void debitRowKeepsTheNegativeSignAndTheReversalFlag() {
        bank.respondBalance(balanceResponse("12345.67", "SUC0000"));
        bank.respondStatement(statementResponse("N", null, false));

        BankDataEntry entry = adapter.collect(context(1, null)).entries().get(0);

        assertEquals("D", entry.vendor().loanCode());
        assertEquals("EXPENSE", entry.direction());
        assertEquals(0, new BigDecimal("-12.00").compareTo(entry.vendor().signedAmount()),
                "the bank signs debits negative and that sign must survive to storage");
        assertEquals(0, new BigDecimal("12.00").compareTo(entry.amount()),
                "the accounting amount stays an unsigned magnitude");
        assertEquals("*", entry.vendor().reversalFlag(),
                "冲账标志 is preserved so a reversal is not silently summed as a normal expense");
        assertEquals(LocalDate.of(2026, 9, 1), entry.vendor().valueDate());
        assertEquals("FEE", entry.vendor().textCode());
        assertEquals("手续费", entry.vendor().remarkTextClt());
    }

    @Test
    void emptyStatementWindowStillReturnsBalanceSnapshot() {
        bank.respondBalance(balanceResponse("888.88", "SUC0000"));
        // Empty window: Z1 says stop (ctnFlag=N), Z2 carries no rows.
        bank.respondStatement("{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\","
                + "\"resultcode\":\"SUC0000\",\"resultmsg\":\"\"},\"body\":{"
                + "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"ctnFlag\":\"N\",\"queryAcctNbr\":\"\","
                + "\"debitNums\":\"0\",\"debitAmount\":\"0\",\"creditNums\":\"0\","
                + "\"creditAmount\":\"0\"}],\"TRANSQUERYBYBREAKPOINT_Z2\":[]}}}");

        BankDataCollection collection = adapter.collect(context(1, null));

        assertEquals("SUC0000", collection.status());
        assertTrue(collection.entries().isEmpty());
        assertEquals(1, collection.balances().size());
        assertFalse(collection.hasMore());
        assertNull(collection.nextCursor());
    }

    @Test
    void surfacesPerAccountBalanceRowError() {
        bank.respondBalance(balanceResponse("0.00", "FAAA002"));
        bank.respondStatement(statementResponse("N", null, false));

        BankDataCollection collection = adapter.collect(context(1, null));

        assertEquals("FAAA002", collection.bankStatusCode());
        assertEquals("FAAA002", collection.status());
        assertTrue(collection.entries().isEmpty());
        assertTrue(collection.balances().isEmpty());
        assertFalse(collection.hasMore());
        // Statement was not queried after the balance-level failure.
        assertEquals(1, bank.requests().size());
    }

    @Test
    void surfacesHeadLevelBalanceFailure() {
        bank.respondBalance("{\"response\":{\"head\":{\"funcode\":\"NTQADINF\","
                + "\"resultcode\":\"FABZ002\",\"resultmsg\":\"业务条件检查失败\"},\"body\":{}}}");

        BankDataCollection collection = adapter.collect(context(1, null));

        assertEquals("FABZ002", collection.bankStatusCode());
        assertTrue(collection.balances().isEmpty());
    }

    @Test
    void surfacesHeadLevelStatementFailure() {
        bank.respondBalance(balanceResponse("12345.67", "SUC0000"));
        bank.respondStatement("{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\","
                + "\"resultcode\":\"FAAQ086\",\"resultmsg\":\"户口不存在\"},\"body\":{}}}");

        BankDataCollection collection = adapter.collect(context(1, null));

        assertEquals("FAAQ086", collection.bankStatusCode());
        assertTrue(collection.entries().isEmpty());
        assertTrue(collection.balances().isEmpty());
    }

    @Test
    void requiresSyncWindow() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> adapter.collect(new BankDataSyncContext(1L, 1L, 10L, "TASK-1", "req-1")));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("window"));
    }

    @Test
    void requiresBankAccountScope() {
        BankDataSyncContext context = new BankDataSyncContext(1L, 1L, null, "TASK-1", "req-1",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 2, 0, 0),
                1, null, 100, "STATEMENT");
        BusinessException error = assertThrows(BusinessException.class, () -> adapter.collect(context));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("bank account scope"));
    }

    @Test
    void requiresResolvableAccountNumber() {
        when(bankAccountMapper.selectOne(any())).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> adapter.collect(context(1, null)));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("not resolvable"));
    }

    @Test
    void rejectsMissingGatewayConfigurationWhenEnabled() {
        RealCmbBankDataAdapter unconfigured =
                new RealCmbBankDataAdapter(new CmbAdapterProperties(), bankAccountMapper);
        BusinessException error = assertThrows(BusinessException.class,
                () -> unconfigured.collect(context(1, null)));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("incomplete"));
    }

    private BankDataSyncContext context(int page, String cursor) {
        return new BankDataSyncContext(1L, 1L, 10L, "TASK-1", "req-cmb-1",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 2, 0, 0),
                page, cursor, 100, "STATEMENT");
    }

    private String balanceResponse(String avlblv, String errcod) {
        return "{\"response\":{\"head\":{\"funcode\":\"NTQADINF\",\"resultcode\":\"SUC0000\","
                + "\"resultmsg\":\"\"},\"body\":{\"ntqadinfz\":[{\"accnbr\":\"" + ACCOUNT_NO + "\","
                + "\"accnam\":\"活期户\",\"avlblv\":\"" + avlblv + "\",\"onlblv\":\"0.00\","
                + "\"hldblv\":\"0.00\",\"stscod\":\"A\",\"errcod\":\"" + errcod + "\","
                + "\"errtxt\":\"\",\"lmtovr\":\"50000.00\",\"intcod\":\"S\","
                + "\"intrat\":\"0.0125000\",\"mutdat\":\"20270519\"}]}}}";
    }

    /**
     * One canned statement page: Z1 ctn control + one Z2 row. {@code withCreditRow=true}
     * produces a credit row (C, +100.50, businessText summary), otherwise a debit row
     * (D, -12.00, remarkTextClt summary). Y1 echoed when ctnFlag=Y.
     */
    private String statementResponse(String ctnFlag, String queryAcctNbr, boolean withCreditRow) {
        String row = withCreditRow
                ? "{\"transDate\":\"20260901\",\"transTime\":\"101530\",\"valueDate\":\"20260902\","
                + "\"transSequenceIdn\":\"T0000000000001\",\"loanCode\":\"C\","
                + "\"transAmount\":\"100.50\",\"currencyNbr\":\"10\",\"textCode\":\"EBPP\","
                + "\"billNumber\":\"BILL-0001\",\"ctpAcctNbr\":\"957151020441242810\","
                + "\"ctpAcctName\":\"对手方公司\",\"businessText\":\"网银业务摘要\","
                + "\"remarkTextClt\":\"你方摘要-代付\",\"acctOnlineBal\":\"12446.17\","
                + "\"extendedRemark\":\"扩展摘要\",\"ctpBankName\":\"招商银行深圳分行\","
                + "\"ctpBankAddress\":\"深圳市\",\"fatOrSonAccount\":\" \","
                + "\"fatOrSonCompanyName\":\" \",\"fatOrSonBankName\":\" \","
                + "\"fatOrSonBankAddress\":\" \",\"infoFlag\":\"1\",\"businessName\":\"批量代付\","
                + "\"requestNbr\":\"RQ00000001\",\"yurRef\":\"YUR-REF-001\",\"virtualNbr\":\" \","
                + "\"mchOrderNbr\":\" \",\"transCardNbr\":\" \",\"reversalFlag\":\"N\","
                + "\"reserve\":\"**\"}"
                : "{\"transDate\":\"20260901\",\"transTime\":\"091500\",\"valueDate\":\"20260901\","
                + "\"transSequenceIdn\":\"T0000000000002\",\"loanCode\":\"D\","
                + "\"transAmount\":\"-12.00\",\"currencyNbr\":\"10\",\"textCode\":\"FEE\","
                + "\"billNumber\":\" \",\"ctpAcctNbr\":\"62220000001234\","
                + "\"ctpAcctName\":\"收款方\",\"businessText\":\" \",\"remarkTextClt\":\"手续费\","
                + "\"acctOnlineBal\":\"12333.67\",\"extendedRemark\":\" \","
                + "\"ctpBankName\":\" \",\"ctpBankAddress\":\" \",\"fatOrSonAccount\":\" \","
                + "\"fatOrSonCompanyName\":\" \",\"fatOrSonBankName\":\" \","
                + "\"fatOrSonBankAddress\":\" \",\"infoFlag\":\" \",\"businessName\":\" \","
                + "\"requestNbr\":\" \",\"yurRef\":\" \",\"virtualNbr\":\" \","
                + "\"mchOrderNbr\":\" \",\"transCardNbr\":\" \",\"reversalFlag\":\"*\","
                + "\"reserve\":\"**\"}";
        String y1 = "Y".equals(ctnFlag)
                ? "\"TRANSQUERYBYBREAKPOINT_Y1\":[{\"acctNbr\":\"" + ACCOUNT_NO
                + "\",\"transDate\":\"20260901\",\"expectNextSequence\":\"2\"}],"
                : "";
        String z1 = "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"ctnFlag\":\"" + ctnFlag
                + "\",\"queryAcctNbr\":\"" + (queryAcctNbr == null ? "" : queryAcctNbr)
                + "\",\"debitNums\":\"0\",\"debitAmount\":\"0\",\"creditNums\":\"1\","
                + "\"creditAmount\":\"100.50\"}],";
        return "{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\",\"resultcode\":\"SUC0000\","
                + "\"resultmsg\":\"\"},\"body\":{" + y1 + z1
                + "\"TRANSQUERYBYBREAKPOINT_Z2\":[" + row + "]}}}";
    }
}
