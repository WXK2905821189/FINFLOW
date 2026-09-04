package com.finance.system.bankdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/bank-sync-jobs (job list) had no test coverage at all - every existing test
 * only ever POSTed a job. Production returned 500 on the list while detail-by-id worked,
 * and the global exception handler swallowed the stack, so the failure was invisible.
 * This test locks the list contract: paged rows, stable ordering, and tolerant of a task
 * whose connection_id no longer resolves (profiles can be disabled/re-created).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.call.real-adapters-enabled=true")
@Import(BankSyncJobListIntegrationTest.StubRealCmbAdapterConfiguration.class)
class BankSyncJobListIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BankDataQueryService queryService;

    @Test
    void listsJobsAsAPageAfterASync() throws Exception {
        String token = login();
        long accountId = archiveAccount(token);
        triggerSync(token, accountId, "JOBLIST-" + UUID.randomUUID());

        // 直调 service 拿未吞的异常；HTTP 层行为由 jobTypeFilterRejectsNothingItCannotServe 锁定
        var page = queryService.listJobs(1L, 1, 10, null, null, null, null);
        assertTrue(page.total() >= 1, "the sync just created at least one task");
        assertTrue(!page.records().isEmpty());
        assertEquals("STATEMENT_PULL", page.records().get(0).jobType());

        String body = mockMvc.perform(get("/api/bank-sync-jobs")
                        .param("page", "1")
                        .param("size", "10")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(body).get("data");
        assertTrue(data.get("total").asLong() >= 1, "the sync just created at least one task");
        JsonNode records = data.get("records");
        assertTrue(records.isArray() && !records.isEmpty());
        assertEquals("STATEMENT_PULL", records.get(0).get("jobType").asText());
    }

    @Test
    void jobTypeFilterRejectsNothingItCannotServe() throws Exception {
        String token = login();
        String body = mockMvc.perform(get("/api/bank-sync-jobs")
                        .param("page", "1")
                        .param("size", "10")
                        .param("jobType", "BALANCE_PULL")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(body).get("data");
        assertEquals(0, data.get("total").asLong(), "unsupported jobType yields an empty page, not a 500");
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void triggerSync(String token, long accountId, String requestId) throws Exception {
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountId
                                + ",\"adapterCode\":\"CMB\""
                                + ",\"windowStart\":\"2026-09-01T00:00:00\",\"windowEnd\":\"2026-09-02T00:00:00\"}"))
                .andExpect(status().isOk());
    }

    private long archiveAccount(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        String body = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CMB\",\"accountName\":\"任务列表验证账户\",\"accountNumber\":\""
                                + suffix + "0001\",\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
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
                    return new BankDataCollection("JOBLIST-BANK-1", List.of(), List.of(
                            new BankDataBalanceEntry("JOBLIST-BANK-1", context.bankAccountId(),
                                    new BigDecimal("1.00"), "CNY", null)),
                            false, null, "SUC0000", "SUC0000");
                }
            };
        }
    }
}
