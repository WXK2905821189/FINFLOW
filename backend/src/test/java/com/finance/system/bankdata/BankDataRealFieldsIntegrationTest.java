package com.finance.system.bankdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankPageTotals;
import com.finance.system.bankdata.adapter.VendorStatementFields;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end lock on the "no business projection" contract: what the bank put on the wire
 * is what lands in storage and what comes back out of the query API, field for field.
 *
 * <p>The balance and statement screens used to collapse 10 bank balance fields into one
 * figure and 30 statement fields into seven, then dress the remainder up in a generic
 * {@code id / occurredAt / amount / direction} projection. Anything the projection did not
 * have a slot for was dropped — including the per-transaction balance, the 起息日 and the
 * 冲账标志. This test exists so that cannot quietly come back: the assertions insist on the
 * bank's own field names, codes and sign convention.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(BankDataRealFieldsIntegrationTest.StubRealCmbAdapterConfiguration.class)
class BankDataRealFieldsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BankDataSyncTaskMapper taskMapper;

    @Test
    void statementQueryReturnsTheBanksOwnFieldsRatherThanAProjection() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = archiveAccount(adminToken);
        triggerSync(adminToken, accountId);

        JsonNode records = query(adminToken, "statements", accountId);
        assertEquals(1, records.size(), "the stub produced exactly one transaction");
        JsonNode row = records.get(0);

        // 银行原始字段，逐列对齐招行 trsQryByBreakPoint 的 Z2
        assertEquals("STUB-REAL-STMT-001", row.get("statementNo").asText());
        assertEquals("D", row.get("loanCode").asText(), "借贷码 is the bank's C/D, not INCOME/EXPENSE");
        assertEquals("EXPENSE", row.get("direction").asText(), "direction stays the derived accounting flag");
        assertEquals(-12.34, row.get("signedAmount").asDouble(), 0.001,
                "the bank signs debits negative and the sign survives storage");
        assertEquals(12.34, row.get("amount").asDouble(), 0.001,
                "the accounting amount stays an unsigned magnitude");
        assertEquals(1233.67, row.get("acctOnlineBal").asDouble(), 0.001, "每笔后余额 is what makes a day verifiable");
        assertEquals("2026-09-02", row.get("valueDate").asText(), "起息日 is not the trade date");
        assertEquals("*", row.get("reversalFlag").asText(), "冲账标志 must survive or reversals get double counted");
        assertEquals("1", row.get("infoFlag").asText());
        assertEquals("FEE", row.get("textCode").asText());
        assertEquals("957151020441242810", row.get("ctpAcctNbr").asText(), "收付方帐号 in full");
        assertEquals("对手方公司", row.get("counterpartyName").asText());
        assertEquals("招商银行深圳分行", row.get("ctpBankName").asText());
        assertEquals("深圳市", row.get("ctpBankAddress").asText());
        assertEquals("你方摘要-手续费", row.get("remarkTextClt").asText());
        assertEquals("网银业务摘要", row.get("businessText").asText());
        assertEquals("扩展摘要", row.get("extendedRemark").asText());
        assertEquals("YUR-REF-001", row.get("yurRef").asText());
        assertEquals("STUB-REAL-BANK-1", row.get("bankRequestNo").asText());

        // 血缘字段：哪次银行调用产出了这一行、那次调用是否落定
        assertNotNull(row.get("taskNo"), "the producing sync task is part of the evidence");
        assertEquals("SUCCEEDED", row.get("taskStatus").asText());
        // 本方账号脱敏，收付方账号不脱敏
        assertTrue(row.get("accountMasked").asText().startsWith("****"),
                "our own account number is masked in the response");
        assertEquals("****0001", row.get("accountMasked").asText());

        // 银行 Z1 口径的窗口合计落库：这是银行自己认的账，独立于我们的去重/校验计数
        BankDataSyncTask task = taskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getBankAccountId, accountId)).get(0);
        assertEquals(new java.math.BigDecimal("-12.34"), task.getDebitAmount(),
                "the bank's own debit total for the window, signed as reported");
        assertEquals(1, task.getDebitNums());
        assertEquals(new java.math.BigDecimal("100.50"), task.getCreditAmount());
        assertEquals(2, task.getCreditNums());
    }

    @Test
    void balanceQueryReturnsAllFourBalanceFiguresTheBankReports() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = archiveAccount(adminToken);
        triggerSync(adminToken, accountId);

        JsonNode records = query(adminToken, "balances", accountId);
        assertEquals(1, records.size());
        JsonNode row = records.get(0);

        // 四个口径不可互相替代：可用 / 联机 / 冻结 / 上日
        assertEquals(816065.34, row.get("availableBalance").asDouble(), 0.001);
        assertEquals(820000.00, row.get("onlineBalance").asDouble(), 0.001);
        assertEquals(3934.66, row.get("frozenBalance").asDouble(), 0.001);
        assertEquals(810000.00, row.get("previousDayBalance").asDouble(), 0.001);
        // 账户身份字段：对账时要能证明这个数字是哪个户的
        assertEquals("10", row.get("vendorCurrencyCode").asText());
        assertEquals("0755", row.get("branchCode").asText());
        assertEquals("1299000000000001", row.get("bankAccountNo").asText());
        assertEquals("上海图虫网络科技有限公司", row.get("bankAccountName").asText());
        assertEquals("2011", row.get("accountItem").asText());
        assertEquals("CR-778899", row.get("customerRelationNo").asText());
        // 账户生命周期字段（NTQADINF Y 必返）：冻结/关户状态直接影响余额数字的可信度
        assertEquals("A", row.get("accountStatus").asText());
        assertEquals("20140519", row.get("openDate").asText());
        assertEquals("ZZZ", row.get("interestType").asText());
        assertEquals("Z(12)", row.get("depositTerm").asText());
        assertNotNull(row.get("taskNo"));
        assertEquals("SUCCEEDED", row.get("taskStatus").asText());
    }

    @Test
    void statementExportSplitsTheSignedAmountIntoTheBanksTwoColumns() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = archiveAccount(adminToken);
        triggerSync(adminToken, accountId);

        var response = mockMvc.perform(get("/api/bank-data/statements/export")
                        .param("accountId", String.valueOf(accountId))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertEquals("text/csv;charset=UTF-8", response.getContentType().replace(" ", ""),
                "the file opens straight in Excel/WPS");
        String csv = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"), "UTF-8 BOM is what makes Excel read Chinese correctly");

        String[] lines = csv.replace("\uFEFF", "").split("\r\n");
        assertEquals("账号,账号名称,币种,交易日,交易时间,起息日,交易类型,借方金额,贷方金额,余额",
                lines[0].substring(0, lines[0].indexOf(",摘要")),
                "column order mirrors the bank's own export, not our storage schema");
        assertEquals(2, lines.length, "exactly the stub's one transaction");
        String[] cells = lines[1].split(",", -1);
        assertEquals("人民币", cells[2], "currencyNbr 10 renders as the bank's own label");
        assertEquals("FEE", cells[6], "交易类型 stays the bank's code");
        assertEquals("12.34", cells[7], "the debit lands in 借方金额 as a positive figure");
        assertEquals("", cells[8], "贷方金额 stays empty - the bank's file is either/or, never both");
        assertEquals("1233.67", cells[9], "余额 is the per-transaction balance");
        assertEquals("2026-09-02", cells[5], "起息日 is its own column, not the trade date");
    }

    @Test
    void balanceExportCarriesAllFourBalanceFigures() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = archiveAccount(adminToken);
        triggerSync(adminToken, accountId);

        String csv = mockMvc.perform(get("/api/bank-data/balances/export")
                        .param("accountId", String.valueOf(accountId))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String[] lines = csv.replace("\uFEFF", "").split("\r\n");
        assertEquals("快照时间,账号,账号名称,币种,可用余额,联机余额,冻结余额,上日余额",
                lines[0].substring(0, lines[0].indexOf(",科目")), "all four figures are columns, none collapsed");
        String[] cells = lines[1].split(",", -1);
        assertEquals("816065.34", cells[4]);
        assertEquals("820000.00", cells[5]);
        assertEquals("3934.66", cells[6]);
        assertEquals("810000.00", cells[7]);
        assertEquals("STUB-REAL-BANK-1", cells[11], "银行请求号 travels with the row as evidence");
    }

    private JsonNode query(String token, String resource, long accountId) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data/" + resource)
                        .param("accountId", String.valueOf(accountId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(body).get("data");
        assertTrue(data.get("enabled").asBoolean(), "a REAL adapter is assembled, so the page is enabled");
        assertEquals("REAL", data.get("status").asText());
        return data.get("records");
    }

    private void triggerSync(String token, long accountId) throws Exception {
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", "REAL-FIELDS-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-02T00:00:00\"}"))
                .andExpect(status().isOk());
    }

    /** Account number ends in 0001 so the masked form is deterministic (****0001). */
    private long archiveAccount(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        String body = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"真实字段验证账户\",\"accountNumber\":\""
                                + suffix + "0001\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Deterministic REAL-mode CMB adapter carrying full vendor detail - no network I/O. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubRealCmbAdapterConfiguration {

        @Bean
        BankDataAdapter stubRealCmbAdapter() {
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "CMB";
                }

                @Override
                public BankAdapterExecutionMode executionMode() {
                    return BankAdapterExecutionMode.REAL;
                }

                @Override
                public BankDataCollection collect(BankDataSyncContext context) {
                    if (context.pageNumber() != null && context.pageNumber() > 1) {
                        return new BankDataCollection("STUB-REAL-BANK-1-P" + context.pageNumber(),
                                List.of(), List.of(), false, null, "SUC0000", "SUC0000");
                    }
                    BankDataEntry entry = new BankDataEntry("STUB-REAL-BANK-1", "STUB-REAL-STMT-001",
                            context.bankAccountId(), context.windowStart().plusHours(9), "EXPENSE",
                            new BigDecimal("12.34"), "CNY", "对手方公司", "957151020441242810",
                            "网银业务摘要", new VendorStatementFields("1299000000000001",
                                    LocalDate.of(2026, 9, 2), "D", "FEE", "BILL-0001",
                                    "你方摘要-手续费", "*", new BigDecimal("1233.67"),
                                    new BigDecimal("-12.34"), "扩展摘要", "957151020441242810",
                                    "招商银行深圳分行", "深圳市", null, null, null, null,
                                    "1", "批量代付", "网银业务摘要", "RQ00000001", "YUR-REF-001",
                                    null, null, null, "**", "10"));
                    BankDataBalanceEntry balance = new BankDataBalanceEntry("STUB-REAL-BANK-1",
                            context.bankAccountId(), new BigDecimal("816065.34"), "CNY",
                            context.windowEnd().minusMinutes(1), new BigDecimal("820000.00"),
                            new BigDecimal("3934.66"), new BigDecimal("810000.00"), "10", "0755",
                            "1299000000000001", "上海图虫网络科技有限公司", "2011", "CR-778899",
                            "A", "20140519", "ZZZ", "Z(12)");
                    return new BankDataCollection("STUB-REAL-BANK-1", List.of(entry), List.of(balance),
                            false, null, "SUC0000", "SUC0000",
                            new BankPageTotals(new BigDecimal("-12.34"), 1L,
                                    new BigDecimal("100.50"), 2L));
                }
            };
        }
    }
}
