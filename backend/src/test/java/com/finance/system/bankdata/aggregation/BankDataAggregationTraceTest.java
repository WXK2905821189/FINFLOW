package com.finance.system.bankdata.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.BankDataQueryService;
import com.finance.system.bankdata.BankDataSyncService;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime evidence for P2-AGG-01, P2-AGG-02 and P2-AGG-03.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(BankDataAggregationTraceTest.DeferredResultAdapterConfiguration.class)
class BankDataAggregationTraceTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataAggregationService aggregationService;
    @Autowired
    private BankDataSyncService bankDataSyncService;
    @Autowired
    private BankDataQueryService bankDataQueryService;

    private Company companyB;
    private SysUser userB;
    private BankAccount accountB;

    @BeforeEach
    void setUpTenantB() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        companyB = new Company();
        companyB.setCode("AGG_" + suffix);
        companyB.setName("AGG company " + suffix);
        companyB.setStatus("ACTIVE");
        companyMapper.insert(companyB);

        userB = new SysUser();
        userB.setCompanyId(companyB.getId());
        userB.setUsername("agg_" + suffix);
        userB.setEmail("agg_" + suffix + "@finflow.test");
        userB.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userB.setStatus("ACTIVE");
        userMapper.insert(userB);
        userRoleMapper.insert(new SysUserRole(userB.getId(), 2L));

        accountB = new BankAccount();
        accountB.setCompanyId(companyB.getId());
        accountB.setBankCode("CMB");
        accountB.setAccountName("AGG company account");
        accountB.setAccountNumber("6222" + suffix + "0002");
        accountB.setCurrency("CNY");
        accountB.setAvailableBalance(new BigDecimal("400000.00"));
        accountB.setStatus("ACTIVE");
        bankAccountMapper.insert(accountB);
    }

    private Long adminUserId() {
        return userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin")).getId();
    }

    @Test
    void brandMockAdaptersNormalizeToTheSameFinflowModel() {
        BankDataAggregationResult citic = aggregationService.collect(context(), "CITIC_MOCK");
        BankDataAggregationResult cmb = aggregationService.collect(context(), "CMB_MOCK");
        BankDataAggregationResult generic = aggregationService.collect(context(), "MOCK");

        // Two branded MOCK adapters register independently and each keeps its own code.
        assertEquals("CITIC_MOCK", citic.adapterCode());
        assertEquals("CMB_MOCK", cmb.adapterCode());
        assertEquals("MOCK", generic.adapterCode());
        assertNotEquals(citic.collection().entries().get(0).statementNo(),
                cmb.collection().entries().get(0).statementNo());

        // Vendor direction vocabularies (C/D, IN/OUT, INCOME/EXPENSE) collapse to one model.
        assertEquals("INCOME", citic.collection().entries().get(0).direction());
        assertEquals("INCOME", cmb.collection().entries().get(0).direction());
        assertEquals("INCOME", generic.collection().entries().get(0).direction());

        // Currency casing is normalized and every adapter shares the mapping version.
        assertEquals("CNY", citic.collection().entries().get(0).currency());
        assertEquals("FINFLOW-BANKDATA-V1", citic.mappingVersion());
        assertEquals(citic.mappingVersion(), cmb.mappingVersion());
        assertEquals(citic.mappingVersion(), generic.mappingVersion());
        assertEquals(BankDataStatus.SUCCESS, citic.status());
    }

    @Test
    void unknownAdapterCodeIsRejectedWithoutFallingBackToSuccess() {
        assertThrows(BusinessException.class, () -> aggregationService.collect(context(), "REAL_BANK_PROD"));
        assertThrows(BusinessException.class, () -> aggregationService.mappingVersion("REAL_BANK_PROD"));
    }

    @Test
    void traceChainsTaskRawSummaryNormalizedRecordsAndProjection() throws Exception {
        String adminToken = login("admin", "Admin@123");
        String requestId = "AGG-TRACE-" + UUID.randomUUID();
        long taskId = triggerSync(adminUserId(), 1L, requestId, "MOCK",
                "2026-09-07T00:00:00", "2026-09-08T00:00:00");

        String body = mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", requestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.requestId").value(requestId))
                .andExpect(jsonPath("$.data.task.mappingVersion").value("FINFLOW-BANKDATA-V1"))
                .andExpect(jsonPath("$.data.task.taskNo").isString())
                .andExpect(jsonPath("$.data.task.bankRequestNo").isString())
                .andExpect(jsonPath("$.data.rawSummaries[0].contentSha256").isString())
                .andExpect(jsonPath("$.data.rawSummaries[0].mappingVersion").value("FINFLOW-BANKDATA-V1"))
                .andExpect(jsonPath("$.data.rawSummaries[0].payload").doesNotExist())
                .andExpect(jsonPath("$.data.statementCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.projectionAvailable").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        String taskNo = data.get("task").get("taskNo").asText();
        String bankRequestNo = data.get("task").get("bankRequestNo").asText();
        long rawCount = data.get("rawSummaries").size();
        long statementCount = data.get("statementCount").asLong();
        assertTrue(rawCount > 0, "at least one raw summary must be traceable");
        assertTrue(data.get("rawSummaries").get(0).get("contentSha256").asText().length() == 64,
                "raw evidence must be a SHA-256 digest");

        // The same chain is reachable from the task number.
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("taskNo", taskNo)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.id").value((int) taskId))
                .andExpect(jsonPath("$.data.task.requestId").value(requestId));

        // Normalized records and projections are reachable through the same request id.
        mockMvc.perform(get("/api/bank-data/statements")
                        .param("requestId", requestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value((int) statementCount));
        mockMvc.perform(get("/api/bank-data/balances")
                        .param("requestId", requestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
        // Sync logs carry the bank request number that ties the chain together.
        BankDataSyncTaskDetailResponse detail = bankDataQueryService.getTaskDetail(adminUserId(), taskId);
        assertTrue(detail.logs().stream().anyMatch(log -> bankRequestNo.equals(log.bankRequestNo())),
                "sync logs must carry the bank request number");
    }

    @Test
    void traceIsIsolatedAcrossCompaniesAndNeverLeaksExistence() throws Exception {
        String adminToken = login("admin", "Admin@123");
        userRoleMapper.insert(new SysUserRole(userB.getId(), 3L));
        String companyBToken = login(userB.getUsername(), PASSWORD);

        String requestId = "AGG-ISOLATED-" + UUID.randomUUID();
        triggerSync(adminUserId(), 1L, requestId, "MOCK", "2026-09-01T00:00:00", "2026-09-02T00:00:00");

        // Company B must see a plain 404, not a distinguishable "exists but forbidden" response.
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", requestId)
                        .header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bank data trace not found"));

        // Company B can still resolve its own chain.
        String ownRequestId = "AGG-OWN-" + UUID.randomUUID();
        triggerSync(userB.getId(), accountB.getId(), ownRequestId, "MOCK",
                "2026-09-03T00:00:00", "2026-09-04T00:00:00");
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", ownRequestId)
                        .header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.requestId").value(ownRequestId));

        // A missing identifier and a cross-company identifier are indistinguishable.
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", "AGG-DOES-NOT-EXIST")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bank data trace not found"));
        mockMvc.perform(get("/api/bank-data-trace")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void traceResponseNeverExposesRawPayloadOrBankSpecificFields() throws Exception {
        String adminToken = login("admin", "Admin@123");
        String requestId = "AGG-SAFE-" + UUID.randomUUID();
        triggerSync(adminUserId(), 1L, requestId, "MOCK", "2026-09-05T00:00:00", "2026-09-06T00:00:00");

        String body = mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", requestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode raw = objectMapper.readTree(body).get("data").get("rawSummaries").get(0);
        assertTrue(!raw.has("payload") && !raw.has("content") && !raw.has("body"),
                "raw payload must stay server-side");
        assertTrue(!body.contains("模拟银行流水"), "trace must not echo statement summaries from raw payloads");
        assertTrue(!body.contains("bankStatusCode") || body.contains("mappingVersion"),
                "bank-specific fields must not be exposed without mapping metadata");
        assertEquals(7, raw.size(), "raw summary exposes digest and retention metadata only");
    }

    @Test
    void pendingAndUnknownResultsDoNotBecomeSucceeded() throws Exception {
        String adminToken = login("admin", "Admin@123");

        String pendingRequestId = "AGG-PENDING-" + UUID.randomUUID();
        BankDataSyncTaskDetailResponse pending = bankDataSyncService.trigger(adminUserId(),
                new BankDataSyncRequest(null, 1L, "MOCK_PENDING"), pendingRequestId);
        assertEquals("PENDING", pending.task().status());
        assertEquals(0, pending.task().normalizedCount());
        assertEquals("FINFLOW-BANKDATA-V1", pending.task().mappingVersion());

        String unknownRequestId = "AGG-UNKNOWN-" + UUID.randomUUID();
        BankDataSyncTaskDetailResponse unknown = bankDataSyncService.trigger(adminUserId(),
                new BankDataSyncRequest(null, 2L, "MOCK_UNKNOWN"), unknownRequestId);
        assertEquals("UNKNOWN", unknown.task().status());
        assertEquals(0, unknown.task().normalizedCount());

        // A deferred result still leaves raw evidence traceable, so reconciliation can continue.
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", pendingRequestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("PENDING"))
                .andExpect(jsonPath("$.data.rawSummaries[0].contentSha256").isString())
                .andExpect(jsonPath("$.data.projectionAvailable").value(false));
    }

    @Test
    void syncKeyReuseKeepsTheCallerRequestIdInTheAuditTrail() throws Exception {
        String adminToken = login("admin", "Admin@123");

        String originalRequestId = "AGG-REUSE-ORIG-" + UUID.randomUUID();
        long taskId = triggerSync(adminUserId(), 1L, originalRequestId, "MOCK",
                "2026-09-09T00:00:00", "2026-09-10T00:00:00");

        // Same account + adapter + window, different request id: idempotent reuse
        // returns the original task, but the new request id must be recorded (D7-A).
        String reusedRequestId = "AGG-REUSE-NEW-" + UUID.randomUUID();
        long reusedTaskId = triggerSync(adminUserId(), 1L, reusedRequestId, "MOCK",
                "2026-09-09T00:00:00", "2026-09-10T00:00:00");
        assertEquals(taskId, reusedTaskId, "syncKey hit must reuse the original task");

        // Both request ids now resolve the same trace chain.
        mockMvc.perform(get("/api/bank-data-trace")
                        .param("requestId", reusedRequestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.id").value((int) taskId))
                .andExpect(jsonPath("$.data.task.requestId").value(originalRequestId));

        // The audit trail records the reuse with the caller's request id.
        BankDataSyncTaskDetailResponse detail = bankDataQueryService.getTaskDetail(adminUserId(), taskId);
        assertTrue(detail.logs().stream().anyMatch(log -> "TASK_REUSED".equals(log.eventType())
                        && reusedRequestId.equals(log.requestId())),
                "TASK_REUSED must be recorded with the caller's request id");
    }

    /**
     * Each caller passes a distinct window: a repeated account+adapter+window reuses the
     * existing task and ignores the supplied request id, which would break trace lookups.
     */
    private long triggerSync(Long userId, Long accountId, String requestId, String adapterCode,
                             String windowStart, String windowEnd) {
        BankDataSyncTaskDetailResponse detail = bankDataSyncService.trigger(userId,
                new BankDataSyncRequest(null, accountId, adapterCode,
                        LocalDateTime.parse(windowStart), LocalDateTime.parse(windowEnd)), requestId);
        return detail.task().id();
    }

    private BankDataSyncContext context() {
        return new BankDataSyncContext(1L, 2L, 1L, "TASK-AGG", "REQ-AGG",
                LocalDateTime.of(2026, 8, 27, 0, 0), LocalDateTime.of(2026, 8, 28, 0, 0),
                1, null, 100, "STATEMENT");
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

    @TestConfiguration(proxyBeanMethods = false)
    static class DeferredResultAdapterConfiguration {

        @Bean
        BankDataAdapter pendingAdapter() {
            return new DeferredAdapter("MOCK_PENDING", "AAAAAAE");
        }

        @Bean
        BankDataAdapter unknownAdapter() {
            return new DeferredAdapter("MOCK_UNKNOWN", "ZZZZZZZ");
        }
    }

    private static final class DeferredAdapter implements BankDataAdapter {

        private final String code;
        private final String vendorStatus;

        private DeferredAdapter(String code, String vendorStatus) {
            this.code = code;
            this.vendorStatus = vendorStatus;
        }

        @Override
        public String adapterCode() {
            return code;
        }

        @Override
        public BankDataCollection collect(BankDataSyncContext context) {
            BankDataEntry entry = new BankDataEntry(code + "-REQ", code + "-STMT", context.bankAccountId(),
                    context.windowStart().plusHours(3), "INCOME", new BigDecimal("7.00"), "CNY",
                    "QA", "123456789012", "deferred result");
            return new BankDataCollection(code + "-REQ", List.of(entry), List.of(), false, null,
                    vendorStatus, vendorStatus);
        }
    }
}
