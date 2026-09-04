package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime evidence for the CMB gap ticket (2026-09-03):
 *
 * <p>Gap 2 - {@code POST /api/bank-accounts} archives {@code bankCode=CMB} (200) because a
 * CmbBankService is registered.
 *
 * <p>Gap 1 - {@code POST /api/bank-sync-jobs} with {@code adapterCode=CMB} triggers the
 * REAL-mode CMB adapter (a deterministic stub in this context) instead of falling back to
 * MOCK: the persisted task carries adapterCode=CMB and real statements/balances land.
 *
 * <p>The real-call master gate is opened via property for this context only; the stub does
 * no network I/O, so the runtime stays deterministic.
 *
 * <p>The stub intentionally echoes the real CMB wire code {@code SUC0000} into both
 * {@code bankStatusCode} and {@code status} (matching RealCmbBankDataAdapter), so the vendor
 * vocabulary mapping in {@code BankDataStatus.fromVendor} is exercised end to end - a bank
 * success code missing from that vocabulary would regress to UNKNOWN and stop projection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(CmbGapClosureIntegrationTest.StubRealCmbAdapterConfiguration.class)
class CmbGapClosureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper syncTaskMapper;
    @Autowired
    private BankDataStatementMapper statementMapper;
    @Autowired
    private BankDataBalanceMapper balanceMapper;

    @Test
    void cmbAccountIsArchivableAndRealAdapterTriggerPersistsAdapterCodeCmb() throws Exception {
        String adminToken = login("admin", "Admin@123");

        // Gap 2: archival is accepted with bankCode=CMB.
        String created = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"招商银行基本户\",\"accountNumber\":\"128965327910000\","
                                + "\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bankCode").value("CMB"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long accountId = objectMapper.readTree(created).get("data").get("id").asLong();

        // Gap 1: an explicit adapterCode=CMB must resolve to the REAL adapter and persist CMB, not MOCK.
        String requestId = "GAP-CMB-" + UUID.randomUUID();
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-03T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        BankDataSyncTask task = syncTaskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getRequestId, requestId));
        assertEquals("CMB", task.getAdapterCode(), "task must persist the REAL CMB adapter, not MOCK");
        assertEquals(accountId, task.getBankAccountId());
        assertEquals("SUCCEEDED", task.getStatus());
        assertTrue(task.getRawCount() > 0, "real stub adapter must yield raw rows");

        assertEquals(2, statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                        .eq(BankDataStatement::getTaskId, task.getId())),
                "one statement per window day must normalize");
        assertEquals(2, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                        .eq(BankDataBalance::getTaskId, task.getId())),
                "one balance snapshot per window day must normalize");

        // The archived account stays visible as an ACTIVE CMB account for future syncs.
        BankAccount account = bankAccountMapper.selectById(accountId);
        assertEquals("CMB", account.getBankCode());
        assertEquals("128965327910000", account.getAccountNumber());
    }

    @Test
    void explicitUnknownAdapterCodeFailsFastInsteadOfSilentlyBecomingMock() throws Exception {
        String adminToken = login("admin", "Admin@123");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String accountNumber = "622" + suffix + "0001" + "1";

        String created = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"招行失败路径账户\",\"accountNumber\":\""
                                + accountNumber + "\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long accountId = objectMapper.readTree(created).get("data").get("id").asLong();

        // No registered adapter carries this explicit code: fail fast instead of a silent MOCK run.
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CITIC_REAL\","
                                + "\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-02T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bank data adapter is not available"));

        assertEquals(0, syncTaskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getBankAccountId, accountId)), "no task may be created for an unresolvable adapter");
    }

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
                    String day = context.windowStart().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
                    if (context.pageNumber() != null && context.pageNumber() > 1) {
                        return new BankDataCollection("STUB-CMB-REQ-" + day + "-P" + context.pageNumber(),
                                List.of(), List.of(), false, null, "SUC0000", "SUC0000");
                    }
                    BankDataEntry entry = new BankDataEntry("STUB-CMB-BANK-" + day,
                            "STUB-CMB-STMT-" + day + "-001", context.bankAccountId(),
                            context.windowStart().plusHours(2), "INCOME", new BigDecimal("100.00"),
                            "CNY", "招行测试对手方", "128965327910000", "stub real cmb statement");
                    BankDataBalanceEntry balance = new BankDataBalanceEntry("STUB-CMB-BANK-" + day,
                            context.bankAccountId(), new BigDecimal("816065.34"), "CNY",
                            context.windowEnd().minusMinutes(1));
                    return new BankDataCollection("STUB-CMB-REQ-" + day, List.of(entry), List.of(balance),
                            false, null, "SUC0000", "SUC0000");
                }
            };
        }
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode tree = objectMapper.readTree(body);
        return tree.get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
