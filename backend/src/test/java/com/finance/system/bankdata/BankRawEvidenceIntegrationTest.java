package com.finance.system.bankdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankExchangeEvidence;
import com.finance.system.bankdata.adapter.cmb.CmbRowMapper;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the ODS evidence layer (V23): a sync whose adapter carries wire
 * evidence stores the bank's verbatim response and the request-side facts in their own
 * columns; the stored verbatim response replays through the current mapping rules to a
 * view identical with the one captured at collection time; and the bank's own Z1 totals
 * reconcile against the normalized rows, task by task.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(BankRawEvidenceIntegrationTest.EvidenceStubAdapterConfiguration.class)
class BankRawEvidenceIntegrationTest {

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
    void evidenceIsStoredReplaysCleanlyAndReconciles() throws Exception {
        String adminToken = login("admin", "Admin@123");
        long accountId = createAccount(adminToken);
        String taskNo = triggerSync(adminToken, accountId);

        // ① evidence columns: the verbatim response and the request facts are persisted.
        JsonNode detail = firstDetail(adminToken, accountId);
        assertTrue(detail.get("hasBankRaw").asBoolean(), "V23 rows must carry the verbatim bank response");
        String verbatim = detail.get("responsePayload").asText();
        assertTrue(verbatim.contains("TRANSQUERYBYBREAKPOINT_Z2"), "the stored response is the bank's own body, not the parsed view");
        assertTrue(detail.get("responsePayloadBytes").asInt() > 0);
        JsonNode evidence = objectMapper.readTree(detail.get("requestEvidence").asText());
        assertEquals("trsQryByBreakPoint", evidence.get("funcode").asText());
        assertEquals(200, evidence.get("httpStatus").asInt());
        assertTrue(evidence.get("plainRequest").asText().length() > 0, "the pre-encryption plaintext request is retained");
        assertNotNull(evidence.get("durationMs"));

        // ② replay: today's rules over yesterday's wire data reproduce the stored view.
        JsonNode replay = postReplay(adminToken, detail.get("id").asLong());
        assertTrue(replay.get("replayable").asBoolean());
        assertTrue(replay.get("matches").asBoolean(),
                "replaying through unchanged rules must reproduce the stored view, got: "
                        + replay.get("differences"));
        assertEquals(1, replay.get("replayedEntryCount").asInt());

        // ③ reconciliation: bank-attested Z1 (0 debit / 1 credit, 100.00) vs platform rows.
        JsonNode rows = fetchReconciliation(adminToken);
        JsonNode taskRow = null;
        for (JsonNode row : rows) {
            if (row.get("taskNo").asText().equals(taskNo)) {
                taskRow = row;
                break;
            }
        }
        assertNotNull(taskRow, "the reconciliation list must contain the task this test created");
        assertEquals(1, taskRow.get("bankCreditNums").asInt());
        assertEquals(0, taskRow.get("bankDebitNums").asInt());
        assertEquals(1, taskRow.get("platformIncomeCount").asInt());
        assertEquals(0, taskRow.get("platformExpenseCount").asInt());
        assertEquals(true, taskRow.get("countConsistent").asBoolean());
        assertEquals(true, taskRow.get("amountConsistent").asBoolean());
    }

    @Test
    void replayIsRefusedWithoutTheRawPermission() throws Exception {
        String unprivilegedToken = createUserWithoutRawPermission();
        mockMvc.perform(post("/api/bank-data-raw-messages/1/replay")
                        .header("Authorization", bearer(unprivilegedToken)))
                .andExpect(status().isForbidden());
    }

    private JsonNode firstDetail(String token, long bankAccountId) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data-raw-messages")
                        .param("accountId", String.valueOf(bankAccountId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        JsonNode records = objectMapper.readTree(body).get("data").get("records");
        assertTrue(records.size() >= 1, "a completed synchronization captures at least one bank response");
        return fetchDetail(token, records.get(0).get("id").asLong());
    }

    private JsonNode fetchDetail(String token, long id) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data-raw-messages/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode postReplay(String token, long id) throws Exception {
        String body = mockMvc.perform(post("/api/bank-data-raw-messages/" + id + "/replay")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode fetchReconciliation(String token) throws Exception {
        String body = mockMvc.perform(get("/api/bank-data/task-reconciliation")
                        .param("size", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data").get("records");
    }

    private String triggerSync(String token, long accountId) throws Exception {
        String requestId = "RAW-EVID-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-01T23:59:59\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data").get("jobNo").asText();
    }

    private long createAccount(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"报文证据验证账户\",\"accountNumber\":\"1289"
                                + suffix + "01\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    /** Role 3 never received the raw message permission (V16 grants it to roles 1 and 2 only). */
    private String createUserWithoutRawPermission() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SysUser user = new SysUser();
        user.setCompanyId(1L);
        user.setUsername("noevid_" + suffix);
        user.setEmail("noevid_" + suffix + "@finflow.test");
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
                .andReturn().getResponse().getContentAsString(UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;

    /**
     * REAL-mode CMB stub whose collection is built by the shared row mapper from a
     * crafted verbatim bank response, so the stored view and the replay are produced by
     * exactly the same rules the production adapter uses - no parallel hand-mapping.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class EvidenceStubAdapterConfiguration {

        @Bean
        BankDataAdapter evidenceStubCmbAdapter() {
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
                    String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    String requestNo = "STUB-EVID-REQ-" + unique;
                    String verbatim = verbatimStatementResponse(unique, requestNo);
                    BankExchangeEvidence evidence = new BankExchangeEvidence(
                            "stub-cmb-gateway", "trsQryByBreakPoint", "{\"stub\":\"plaintext-request\"}",
                            verbatim, 5L, 200, null);
                    BankDataCollection parsed = CmbRowMapper.replayStatementPage(verbatim,
                            context.bankAccountId(), requestNo);
                    return new BankDataCollection(parsed.bankRequestNo(), parsed.entries(), List.of(),
                            parsed.hasMore(), parsed.nextCursor(), parsed.bankStatusCode(), parsed.status(),
                            parsed.pageTotals(), evidence);
                }
            };
        }

        /**
         * A minimal but structurally faithful trsQryByBreakPoint page: Z1 continuation
         * control with the bank's own totals, one Z2 credit row, no continuation.
         */
        private static String verbatimStatementResponse(String unique, String requestNo) {
            return "{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\",\"reqid\":\"" + requestNo
                    + "\",\"rspid\":\"RSP-1\",\"resultcode\":\"SUC0000\",\"resultmsg\":\"success\"},"
                    + "\"body\":{"
                    + "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"ctnFlag\":\"N\",\"queryAcctNbr\":\"1289"
                    + unique + "01\",\"debitNums\":\"0\",\"debitAmount\":\"0\",\"creditNums\":\"1\","
                    + "\"creditAmount\":\"100.00\"}],"
                    + "\"TRANSQUERYBYBREAKPOINT_Z2\":[{\"transDate\":\"20260901\",\"transSequenceIdn\":\"STMT-"
                    + unique + "\",\"transTime\":\"103000\",\"valueDate\":\"20260901\",\"loanCode\":\"C\","
                    + "\"transAmount\":\"100.00\",\"currencyNbr\":\"10\",\"textCode\":\"NORW\",\"billNumber\":\"\","
                    + "\"remarkTextClt\":\"\",\"reversalFlag\":\"N\",\"acctOnlineBal\":\"816065.34\","
                    + "\"extendedRemark\":\"\",\"ctpAcctNbr\":\"128965327910000\",\"ctpAcctName\":\"招行测试对手方\","
                    + "\"ctpBankName\":\"\",\"ctpBankAddress\":\"\",\"fatOrSonAccount\":\"\","
                    + "\"fatOrSonCompanyName\":\"\",\"fatOrSonBankName\":\"\",\"fatOrSonBankAddress\":\"\","
                    + "\"infoFlag\":\"\",\"businessName\":\"\",\"businessText\":\"stub evidence statement\","
                    + "\"requestNbr\":\"\",\"yurRef\":\"\",\"virtualNbr\":\"\",\"mchOrderNbr\":\"\","
                    + "\"transCardNbr\":\"\",\"reserve\":\"\"}]}}}";
        }
    }
}
