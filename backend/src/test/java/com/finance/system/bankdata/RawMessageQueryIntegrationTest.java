package com.finance.system.bankdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof for the raw message module: a bank response that was captured
 * during a real synchronization can be browsed and opened in full.
 *
 * <p>The module exists because a digest only proves a record was stored - only the
 * payload proves the bank actually answered. So the assertions here insist on both:
 * the listing reports {@code realDirect} on the strength of the REAL-mode adapter
 * assembly, and the detail hands back a non-empty body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(RawMessageQueryIntegrationTest.StubRealCmbAdapterConfiguration.class)
class RawMessageQueryIntegrationTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Test
    void capturedResponseIsBrowsableAndItsPayloadCanBeOpened() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = archiveCmbAccount(adminToken);
        triggerSync(adminToken, accountId);

        // Scoped to this test's own account on purpose: every Spring context in the suite
        // shares one in-memory H2 instance, so an unfiltered listing would also return the
        // MOCK-adapter responses other tests captured and this assertion would not be
        // testing what it claims to test.
        JsonNode records = fetchRawMessages(adminToken, accountId);
        // One page of the bank query is one HTTP round trip, so a window that continues
        // past page 1 captures one response per page - never fewer than one.
        assertTrue(records.size() >= 1, "a completed synchronization captures at least one bank response");
        for (JsonNode row : records) {
            assertEquals("CMB", row.get("adapterCode").asText());
            assertTrue(row.get("realDirect").asBoolean(),
                    "the response came from a REAL-mode adapter, so it is connectivity evidence");
            assertTrue(row.get("contentSha256").asText().length() > 0);
        }
        JsonNode row = records.get(0);

        JsonNode detail = fetchDetail(adminToken, row.get("id").asLong());
        assertTrue(detail.get("payload").asText().length() > 0, "the raw body is what proves the bank answered");
        assertTrue(detail.get("payloadBytes").asInt() > 0);
        assertTrue(detail.get("realDirect").asBoolean());
    }

    @Test
    void usersWithoutTheRawPermissionAreRefused() throws Exception {
        String unprivilegedToken = createUserWithoutRawPermission();

        mockMvc.perform(get("/api/bank-data-raw-messages")
                        .header("Authorization", bearer(unprivilegedToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/bank-data-raw-messages/1")
                        .header("Authorization", bearer(unprivilegedToken)))
                .andExpect(status().isForbidden());
    }

    private JsonNode fetchRawMessages(String token, long bankAccountId) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data-raw-messages")
                        .param("accountId", String.valueOf(bankAccountId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("records");
    }

    private JsonNode fetchDetail(String token, long id) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data-raw-messages/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private void triggerSync(String token, long accountId) throws Exception {
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", "RAW-MSG-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-03T00:00:00\"}"))
                .andExpect(status().isOk());
    }

    private long archiveCmbAccount(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"原始报文验证账户\",\"accountNumber\":\"1289"
                                + suffix + "01\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    /** Role 3 never received the raw message permission (V16 grants it to roles 1 and 2 only). */
    private String createUserWithoutRawPermission() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SysUser user = new SysUser();
        user.setCompanyId(1L);
        user.setUsername("noraw_" + suffix);
        user.setEmail("noraw_" + suffix + "@finflow.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getId(), 3L));
        return login(user.getUsername(), PASSWORD);
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

    /** Deterministic REAL-mode CMB adapter - no network I/O. */
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
}
