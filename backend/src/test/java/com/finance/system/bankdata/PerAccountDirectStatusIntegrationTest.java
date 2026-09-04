package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof for the reported bug: "没接入中信，却显示中信银行账户连接成功".
 *
 * <p>The account list API must answer per account, not per bank and never with a global flag:
 * <ul>
 *   <li>a CITIC account archived explicitly by this test stays NOT_CONNECTED while CMB is wired
 *       (V15 removed the two CITIC rows V1 used to seed, so the control row is created here and
 *       the proof no longer depends on seed data);</li>
 *   <li>a freshly archived CMB account is ONBOARDED (bank wired, account not yet verified);</li>
 *   <li>only after a real sync succeeded for THAT account does it become DIRECT_CONNECTED.</li>
 * </ul>
 *
 * <p>A deterministic REAL-mode CMB stub stands in for the bank, so the scenario is reproducible
 * without network access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(PerAccountDirectStatusIntegrationTest.StubRealCmbAdapterConfiguration.class)
class PerAccountDirectStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BankAccountMapper accountMapper;

    @Test
    void accountRowsAreJudgedIndividuallyRatherThanByOneGlobalConnectionFlag() throws Exception {
        String adminToken = login("admin", "Admin@123");

        // CITIC has no REAL adapter wired in this context, so archiving an account there must not
        // read as connected - this is the exact row shape that used to inherit the CMB verdict.
        long citicAccountId = archiveAccount(adminToken, "CITIC", "未接通银行对照账户");
        JsonNode citicRow = accountById(fetchAccounts(adminToken), citicAccountId);
        assertEquals("NOT_CONNECTED", citicRow.get("directStatus").asText(),
                "CITIC is not wired: a global CMB connection flag must never colour this row");
        assertNull(citicRow.get("lastRealSyncAt").textValue());

        // A brand new CMB account: bank is wired, but this account has never been verified.
        long cmbAccountId = archiveCmbAccount(adminToken);
        JsonNode fresh = accountById(fetchAccounts(adminToken), cmbAccountId);
        assertEquals("ONBOARDED", fresh.get("directStatus").asText(),
                "wired bank + no successful real sync yet = onboarded, explicitly not connected");
        assertNull(fresh.get("lastRealSyncAt").textValue());

        // After a real sync succeeded for this account, only this row flips to connected.
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .header("X-Request-Id", "DIRECT-STATUS-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + cmbAccountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-03T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        JsonNode accounts = fetchAccounts(adminToken);
        JsonNode verified = accountById(accounts, cmbAccountId);
        assertEquals("DIRECT_CONNECTED", verified.get("directStatus").asText());
        assertNotNull(verified.get("lastRealSyncAt").textValue(),
                "the successful sync timestamp is the evidence backing the connected verdict");

        JsonNode citicAfter = accountById(accounts, citicAccountId);
        assertEquals("NOT_CONNECTED", citicAfter.get("directStatus").asText(),
                "verifying a CMB account must not change the CITIC verdict");
    }

    @Test
    void v15RetiredTheSeededCiticAccounts() {
        // V1 seeded two CITIC demo accounts carrying simulated balances. They are the rows that
        // made an unwired bank look funded, so V15 removes them outright: no surface may quote a
        // balance for a bank that was never connected.
        long remaining = accountMapper.selectCount(new LambdaQueryWrapper<BankAccount>()
                .in(BankAccount::getAccountNumber,
                        List.of("6222000000004821", "6222000000007306")));
        assertEquals(0, remaining,
                "the v0.1 CITIC demo accounts must stay retired - they carry simulated balances");
    }

    @Test
    void newlyArchivedCmbAccountIsOnboardedRatherThanConnected() throws Exception {
        String adminToken = login("admin", "Admin@123");
        mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"建档即未验证账户\",\"accountNumber\":\"1289"
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                                + "01\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directStatus").value("ONBOARDED"));
    }

    private JsonNode fetchAccounts(String adminToken) throws Exception {
        String body = mockMvc.perform(get("/api/bank-accounts")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode accountById(JsonNode accounts, long id) {
        for (JsonNode node : accounts) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("account " + id + " missing from the list response");
    }

    private long archiveCmbAccount(String adminToken) throws Exception {
        return archiveAccount(adminToken, "CMB", "账户级直联验证账户");
    }

    private long archiveAccount(String adminToken, String bankCode, String accountName) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"" + bankCode + "\",\"accountName\":\"" + accountName
                                + "\",\"accountNumber\":\"1289"
                                + suffix + "01\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    /** Deterministic REAL-mode CMB adapter - proves "CMB is assembled" without touching the bank. */
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
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
