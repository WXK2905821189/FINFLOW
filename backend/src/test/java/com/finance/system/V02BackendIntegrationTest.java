package com.finance.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bank.citic.CiticBankSdkClient;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.BankDataRawRetentionService;
import com.finance.system.bankdata.BankDataRetentionProperties;
import com.finance.system.bankdata.BankDataSyncService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.entity.SysRolePermission;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.domain.mapper.SysRoleMapper;
import com.finance.system.domain.mapper.SysRolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(V02BackendIntegrationTest.FaultInjectingAdapterConfiguration.class)
class V02BackendIntegrationTest {

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
    private SysRoleMapper roleMapper;
    @Autowired
    private SysRolePermissionMapper rolePermissionMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper syncTaskMapper;
    @Autowired
    private BankDataRawMessageMapper rawMessageMapper;
    @Autowired
    private BankDataSyncLogMapper syncLogMapper;
    @Autowired
    private BankDataStatementMapper bankDataStatementMapper;
    @Autowired
    private BankDataBalanceMapper bankDataBalanceMapper;
    @Autowired
    private ConnectionProfileMapper connectionProfileMapper;
    @Autowired
    private BankDataSyncService bankDataSyncService;
    @Autowired
    private BankDataRawRetentionService rawRetentionService;
    @Autowired
    private BankDataRetentionProperties retentionProperties;

    @SpyBean
    private CiticBankSdkClient citicBankSdkClient;

    private Company companyB;
    private SysUser userB;
    private BankAccount accountB;

    @BeforeEach
    void setUpTenantB() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        companyB = new Company();
        companyB.setCode("QA_" + suffix);
        companyB.setName("QA company " + suffix);
        companyB.setStatus("ACTIVE");
        companyMapper.insert(companyB);

        userB = new SysUser();
        userB.setCompanyId(companyB.getId());
        userB.setUsername("qa_" + suffix);
        userB.setEmail("qa_" + suffix + "@finflow.test");
        userB.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userB.setStatus("ACTIVE");
        userMapper.insert(userB);
        userRoleMapper.insert(new SysUserRole(userB.getId(), 2L));

        accountB = new BankAccount();
        accountB.setCompanyId(companyB.getId());
        accountB.setBankCode("CITIC");
        accountB.setAccountName("QA company account");
        accountB.setAccountNumber("6222" + suffix + "0001");
        accountB.setCurrency("CNY");
        accountB.setAvailableBalance(new BigDecimal("500000.00"));
        accountB.setStatus("ACTIVE");
        bankAccountMapper.insert(accountB);
    }

    @AfterEach
    void resetFaultInjectors() {
        org.mockito.Mockito.reset(citicBankSdkClient);
    }

    @Test
    void loginMeAndLogoutRevokesTheIssuedSession() throws Exception {
        String token = login("admin", "Admin@123");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statementsAndBankDataProjectionsAreIsolatedByAuthenticatedCompany() throws Exception {
        String adminToken = login("admin", "Admin@123");
        userRoleMapper.insert(new SysUserRole(userB.getId(), 3L));
        String companyBToken = login(userB.getUsername(), PASSWORD);

        String sharedStatementNo = "QA-SHARED-STATEMENT-" + UUID.randomUUID();
        long statementAId = importStatement(adminToken, 1L, sharedStatementNo);
        long statementBId = importStatement(companyBToken, accountB.getId(), sharedStatementNo);

        mockMvc.perform(get("/api/statements").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].id").value(org.hamcrest.Matchers.hasItem((int) statementAId)))
                .andExpect(jsonPath("$.data.records[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) statementBId))));
        mockMvc.perform(get("/api/statements").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].id").value(org.hamcrest.Matchers.hasItem((int) statementBId)))
                .andExpect(jsonPath("$.data.records[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) statementAId))));
        mockMvc.perform(get("/api/statements/" + statementAId).header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statements/" + statementAId + "/review")
                        .header("Authorization", bearer(companyBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/statements/" + statementAId + "/voucher-push")
                        .header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());

        String syncARequestId = "QA-BANK-A-" + UUID.randomUUID();
        long syncAId = triggerBankData(adminToken, 1L, syncARequestId);
        assertEquals(syncAId, triggerBankData(adminToken, 1L, syncARequestId));
        mockMvc.perform(post("/api/bank-data/sync-tasks")
                        .header("Authorization", bearer(adminToken)).header("X-Request-Id", syncARequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccountId\":2,\"adapterCode\":\"MOCK\"}"))
                .andExpect(status().isConflict());
        long syncBId = triggerBankData(companyBToken, accountB.getId(), "QA-BANK-B-" + UUID.randomUUID());
        String statementAKey = "MOCK-STATEMENT-1-1-20260827-P1";
        String statementBKey = "MOCK-STATEMENT-" + companyB.getId() + "-" + accountB.getId() + "-20260827-P1";

        mockMvc.perform(get("/api/bank-data/statement-records").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].statementNo").value(org.hamcrest.Matchers.hasItem(statementAKey)))
                .andExpect(jsonPath("$.data.records[*].statementNo").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(statementBKey))));
        mockMvc.perform(get("/api/bank-data/statement-records").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].statementNo").value(org.hamcrest.Matchers.hasItem(statementBKey)))
                .andExpect(jsonPath("$.data.records[*].statementNo").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(statementAKey))));
        mockMvc.perform(get("/api/bank-data/sync-tasks/" + syncAId).header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());
        assertNotNull(syncTaskMapper.selectById(syncBId));
    }

    @Test
    void bankSyncJobRequiresExplicitScopedAccount() throws Exception {
        String adminToken = login("admin", "Admin@123");
        userRoleMapper.insert(new SysUserRole(userB.getId(), 3L));
        String companyBToken = login(userB.getUsername(), PASSWORD);

        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountB.getId() + "}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(companyBToken))
                        .header("X-Request-Id", "QA-JOB-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountB.getId()
                                + ",\"windowStart\":\"2026-08-27T00:00:00\",\"windowEnd\":\"2026-08-28T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobNo").isString())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void transferWorkflowEnforcesIdempotencySegregationAndUnknownOutcome() throws Exception {
        SysUser creator = createUser("qa_creator_" + UUID.randomUUID(), 1L, 2L);
        userRoleMapper.insert(new SysUserRole(creator.getId(), 3L));
        String creatorToken = login(creator.getUsername(), PASSWORD);
        SysUser manager = createUser("qa_manager_" + UUID.randomUUID(), 1L, 3L);
        String managerToken = login(manager.getUsername(), PASSWORD);
        String idempotencyKey = "QA-PAY-" + UUID.randomUUID();
        String createRequestId = "QA-PAY-CREATE-" + UUID.randomUUID();
        String approveRequestId = "QA-PAY-APPROVE-" + UUID.randomUUID();
        String executeRequestId = "QA-PAY-EXECUTE-" + UUID.randomUUID();
        String body = transferBody(1L, "QA payee", "123456789012", "CITIC", "100.00", "first remark");

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(creatorToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        String first = mockMvc.perform(post("/api/transfers")
                .header("Authorization", bearer(creatorToken))
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Request-Id", createRequestId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode firstData = objectMapper.readTree(first).get("data");
        long paymentId = firstData.get("paymentId").asLong();
        String paymentNo = firstData.get("paymentNo").asText();
        String companyBToken = login(userB.getUsername(), PASSWORD);
        mockMvc.perform(get("/api/transfers/" + paymentId)
                        .header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());

        String replay = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(creatorToken))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(paymentNo, objectMapper.readTree(replay).get("data").get("paymentNo").asText());

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(creatorToken))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(1L, "QA payee", "123456789012", "CITIC", "100.00", "different remark")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/transfers/" + paymentId + "/approve")
                        .header("Authorization", bearer(creatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/transfers/" + paymentId + "/approve")
                        .header("Authorization", bearer(managerToken))
                        .header("X-Request-Id", approveRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        BigDecimal before = bankAccountMapper.selectById(1L).getAvailableBalance();
        mockMvc.perform(post("/api/transfers/" + paymentId + "/execute")
                        .header("Authorization", bearer(creatorToken))
                        .header("X-Request-Id", executeRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        assertEquals(0, before.compareTo(bankAccountMapper.selectById(1L).getAvailableBalance()));
        mockMvc.perform(post("/api/transfers/" + paymentId + "/execute")
                        .header("Authorization", bearer(creatorToken)))
                .andExpect(status().isConflict());

        String unknownKey = "QA-PAY-UNKNOWN-" + UUID.randomUUID();
        String unknownCreateRequestId = "QA-UNKNOWN-CREATE-" + UUID.randomUUID();
        String unknownApproveRequestId = "QA-UNKNOWN-APPROVE-" + UUID.randomUUID();
        String unknownExecuteRequestId = "QA-UNKNOWN-EXECUTE-" + UUID.randomUUID();
        String unknownResolveRequestId = "QA-UNKNOWN-RESOLVE-" + UUID.randomUUID();
        String unknown = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(creatorToken))
                        .header("Idempotency-Key", unknownKey)
                        .header("X-Request-Id", unknownCreateRequestId)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(1L, "unknown payee", "123456789013", "CITIC", "101.00", "unknown")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long unknownId = objectMapper.readTree(unknown).get("data").get("paymentId").asLong();
        doThrow(new IllegalStateException("simulated transport timeout")).when(citicBankSdkClient)
                .submitPayment(ArgumentMatchers.any(), ArgumentMatchers.any());
        mockMvc.perform(post("/api/transfers/" + unknownId + "/approve")
                        .header("Authorization", bearer(managerToken))
                        .header("X-Request-Id", unknownApproveRequestId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/transfers/" + unknownId + "/execute")
                        .header("Authorization", bearer(creatorToken))
                        .header("X-Request-Id", unknownExecuteRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("reconciliation")))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("timeout"))));
        mockMvc.perform(post("/api/transfers/" + unknownId + "/execute")
                        .header("Authorization", bearer(creatorToken)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/transfers").param("status", "UNKNOWN")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].paymentId")
                        .value(org.hamcrest.Matchers.hasItem((int) unknownId)));
        mockMvc.perform(get("/api/transfers").param("status", "UNKNOWN")
                        .header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].paymentId")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) unknownId))));

        String resolutionBody = "{\"action\":\"CONFIRM_SUBMITTED\",\"externalReference\":\"CITIC-MANUAL-0001\","
                + "\"comment\":\"Confirmed against the operator console\"}";
        mockMvc.perform(post("/api/transfers/" + unknownId + "/resolve")
                        .header("Authorization", bearer(creatorToken))
                        .header("X-Request-Id", unknownResolveRequestId)
                        .contentType(MediaType.APPLICATION_JSON).content(resolutionBody))
                .andExpect(status().isForbidden());
        verify(citicBankSdkClient, times(2))
                .submitPayment(ArgumentMatchers.any(), ArgumentMatchers.any());

        mockMvc.perform(post("/api/transfers/" + unknownId + "/resolve")
                        .header("Authorization", bearer(managerToken))
                        .header("X-Request-Id", unknownResolveRequestId)
                        .contentType(MediaType.APPLICATION_JSON).content(resolutionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.bankReference").value("CITIC-MANUAL-0001"));
        mockMvc.perform(post("/api/transfers/" + unknownId + "/resolve")
                        .header("Authorization", bearer(managerToken))
                        .header("X-Request-Id", "QA-UNKNOWN-RESOLVE-REPLAY")
                        .contentType(MediaType.APPLICATION_JSON).content(resolutionBody))
                .andExpect(status().isConflict());
        verify(citicBankSdkClient, times(2))
                .submitPayment(ArgumentMatchers.any(), ArgumentMatchers.any());

        mockMvc.perform(get("/api/transfers").param("status", "UNKNOWN")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].paymentId")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) unknownId))));
        mockMvc.perform(get("/api/transfers/" + unknownId + "/audit-events")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[0].requestId").value(unknownCreateRequestId))
                .andExpect(jsonPath("$.data[1].action").value("APPROVE"))
                .andExpect(jsonPath("$.data[1].requestId").value(unknownApproveRequestId))
                .andExpect(jsonPath("$.data[2].action").value("EXECUTE"))
                .andExpect(jsonPath("$.data[2].requestId").value(unknownExecuteRequestId))
                .andExpect(jsonPath("$.data[3].action").value("RESOLVE_UNKNOWN"))
                .andExpect(jsonPath("$.data[3].requestId").value(unknownResolveRequestId));
    }

    @Test
    void bankDataProjectionRoutesEnforceTenantScopePermissionsAndSafeMockBoundary() throws Exception {
        String adminToken = login("admin", "Admin@123");
        userRoleMapper.insert(new SysUserRole(userB.getId(), 3L));
        String companyBToken = login(userB.getUsername(), PASSWORD);
        SysUser unprivileged = new SysUser();
        String unprivilegedUsername = "qa_no_access_" + UUID.randomUUID();
        unprivileged.setCompanyId(companyB.getId());
        unprivileged.setUsername(unprivilegedUsername);
        unprivileged.setEmail(unprivilegedUsername + "@finflow.test");
        unprivileged.setPasswordHash(passwordEncoder.encode(PASSWORD));
        unprivileged.setStatus("ACTIVE");
        userMapper.insert(unprivileged);
        String unprivilegedToken = login(unprivilegedUsername, PASSWORD);

        String adminRequestId = "QA-PROJECTION-A-" + UUID.randomUUID();
        String companyBRequestId = "QA-PROJECTION-B-" + UUID.randomUUID();
        triggerBankData(adminToken, 1L, adminRequestId);
        triggerBankData(companyBToken, accountB.getId(), companyBRequestId);

        for (String resource : java.util.List.of("balances", "statements", "receipts", "reconciliations", "payments", "payroll")) {
            mockMvc.perform(get("/api/bank-data/" + resource)
                            .header("Authorization", bearer(companyBToken))
                            .param("page", "1")
                            .param("size", "10")
                            .param("requestId", companyBRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.simulated").value(true))
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("normalization-failure"))));

            mockMvc.perform(get("/api/bank-data/" + resource)
                            .header("Authorization", bearer(companyBToken))
                            .param("requestId", adminRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.message").value(
                            "No projection matches the requested task or request"));
        }

        mockMvc.perform(get("/api/bank-data/balances").header("Authorization", bearer(unprivilegedToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bank-data/not-a-resource").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void bankDataProjectionRequiresTheSpecificResourcePermission() throws Exception {
        SysRole balanceOnlyRole = new SysRole();
        balanceOnlyRole.setCode("QA_BALANCE_ONLY_" + UUID.randomUUID());
        balanceOnlyRole.setName("QA balance only");
        balanceOnlyRole.setDescription("Resource permission regression role");
        roleMapper.insert(balanceOnlyRole);
        rolePermissionMapper.insert(new SysRolePermission(balanceOnlyRole.getId(), 24L));

        SysUser balanceOnlyUser = new SysUser();
        String username = "qa_balance_only_" + UUID.randomUUID();
        balanceOnlyUser.setCompanyId(companyB.getId());
        balanceOnlyUser.setUsername(username);
        balanceOnlyUser.setEmail(username + "@finflow.test");
        balanceOnlyUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        balanceOnlyUser.setStatus("ACTIVE");
        userMapper.insert(balanceOnlyUser);
        userRoleMapper.insert(new SysUserRole(balanceOnlyUser.getId(), balanceOnlyRole.getId()));
        String token = login(username, PASSWORD);

        mockMvc.perform(get("/api/bank-data/balances").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/bank-data/payments").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rawResponseAndFailureLogSurviveNormalizationRollback() throws Exception {
        String token = login(userB.getUsername(), PASSWORD);
        String requestId = "QA-RAW-FAIL-" + UUID.randomUUID();

        String response = mockMvc.perform(post("/api/bank-data/sync-tasks")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccountId\":" + accountB.getId() + ",\"adapterCode\":\"MOCK_FAIL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(response).get("data").get("task").get("id").asLong();
        assertEquals("Bank data synchronization failed during internal processing",
                objectMapper.readTree(response).get("data").get("task").get("errorMessage").asText());

        assertTrue(rawMessageMapper.selectCount(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getTaskId, taskId)
                .eq(BankDataRawMessage::getCompanyId, companyB.getId())) > 0);
        assertTrue(syncLogMapper.selectCount(new LambdaQueryWrapper<BankDataSyncLog>()
                .eq(BankDataSyncLog::getTaskId, taskId)
                .eq(BankDataSyncLog::getEventType, "RAW_MESSAGE_PERSISTED")) > 0);
        assertTrue(syncLogMapper.selectCount(new LambdaQueryWrapper<BankDataSyncLog>()
                .eq(BankDataSyncLog::getTaskId, taskId)
                .eq(BankDataSyncLog::getEventType, "SYNC_FAILED")) > 0);
        BankDataSyncLog failureLog = syncLogMapper.selectOne(new LambdaQueryWrapper<BankDataSyncLog>()
                .eq(BankDataSyncLog::getTaskId, taskId)
                .eq(BankDataSyncLog::getEventType, "SYNC_FAILED"));
        assertEquals("Bank data synchronization failed during internal processing", failureLog.getMessage());
        assertTrue(rawMessageMapper.selectList(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getTaskId, taskId))
                .get(0).getPayload().contains("normalization-failure"));
    }

    @Test
    void bankDataPhaseTwoHandlesWindowsPaginationDedupAndRetentionCleanup() throws Exception {
        String token = login(userB.getUsername(), PASSWORD);
        String requestId = "QA-PHASE2-" + UUID.randomUUID();

        String response = mockMvc.perform(post("/api/bank-data/sync-tasks")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccountId\":" + accountB.getId()
                                + ",\"adapterCode\":\"MOCK_PHASE2\","
                                + "\"windowStart\":\"2026-08-26T12:00:00\","
                                + "\"windowEnd\":\"2026-08-28T06:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.task.rawCount").value(7))
                .andExpect(jsonPath("$.data.task.normalizedCount").value(4))
                .andExpect(jsonPath("$.data.task.duplicateCount").value(3))
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(response).get("data").get("task").get("id").asLong();

        assertEquals(taskId, triggerPhase2BankData(token, accountB.getId(), requestId));
        assertEquals(6, rawMessageMapper.selectCount(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getTaskId, taskId)));

        java.util.List<BankDataStatement> statements = bankDataStatementMapper.selectList(
                new LambdaQueryWrapper<BankDataStatement>()
                        .eq(BankDataStatement::getTaskId, taskId)
                        .orderByAsc(BankDataStatement::getTransactionTime));
        assertEquals(3, statements.size());
        assertEquals("PHASE2-20260826-001", statements.get(0).getStatementNo());
        assertEquals("PHASE2-20260827-001", statements.get(1).getStatementNo());
        assertEquals("PHASE2-20260828-001", statements.get(2).getStatementNo());
        assertEquals(1, bankDataBalanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getTaskId, taskId)));

        BankDataRawMessage raw = rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getTaskId, taskId)
                .last("LIMIT 1"));
        raw.setRetentionUntil(LocalDateTime.now().minusDays(1));
        rawMessageMapper.updateById(raw);
        retentionProperties.setCleanupEnabled(false);
        assertEquals(0, rawRetentionService.cleanupExpiredRawPayloads());
        retentionProperties.setCleanupEnabled(true);
        retentionProperties.setBatchLimit(1);
        assertEquals(1, rawRetentionService.cleanupExpiredRawPayloads());
        BankDataRawMessage purged = rawMessageMapper.selectById(raw.getId());
        assertNotNull(purged.getPurgedAt());
        assertTrue(purged.getPayload().contains("[PURGED]"));
        assertEquals(3, bankDataStatementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getTaskId, taskId)));
        assertTrue(syncLogMapper.selectCount(new LambdaQueryWrapper<BankDataSyncLog>()
                .eq(BankDataSyncLog::getTaskId, taskId)
                .eq(BankDataSyncLog::getEventType, "RAW_PAYLOAD_PURGED")) > 0);
        retentionProperties.setCleanupEnabled(false);
    }

    @Test
    void scheduledBankDataSyncReusesRequestIdentityForSameWindow() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setCompanyId(companyB.getId());
        profile.setConnectionCode("QA-PHASE2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        profile.setDisplayName("QA phase2 connection");
        profile.setProviderType("MOCK");
        profile.setEnabled(true);
        profile.setStatus("SIMULATED");
        connectionProfileMapper.insert(profile);

        bankDataSyncService.triggerScheduledSyncs();
        bankDataSyncService.triggerScheduledSyncs();

        assertEquals(1, syncTaskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyB.getId())
                .eq(BankDataSyncTask::getConnectionId, profile.getId())
                .eq(BankDataSyncTask::getTriggerType, "SCHEDULED")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectingAdapterConfiguration {

        @Bean
        BankDataAdapter faultInjectingAdapter() {
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "MOCK_FAIL";
                }

                @Override
                public BankDataCollection collect(com.finance.system.bankdata.adapter.BankDataSyncContext context) {
                    return new BankDataCollection("MOCK-FAIL-RAW", java.util.List.of(new BankDataEntry(
                            "MOCK-FAIL-RAW", "MOCK-FAIL-STATEMENT", context.bankAccountId(),
                            LocalDateTime.of(2026, 8, 27, 9, 0), "INCOME", new BigDecimal("10.00"),
                            "CNY", "QA", "normalization-failure", "x".repeat(300))));
                }
            };
        }

        @Bean
        BankDataAdapter phaseTwoAdapter() {
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "MOCK_PHASE2";
                }

                @Override
                public BankDataCollection collect(BankDataSyncContext context) {
                    String date = String.format(java.util.Locale.ROOT, "%04d%02d%02d",
                            context.windowStart().getYear(), context.windowStart().getMonthValue(),
                            context.windowStart().getDayOfMonth());
                    if (context.pageNumber() != null && context.pageNumber() > 1) {
                        return new BankDataCollection("PHASE2-REQ-" + date + "-P2", java.util.List.of(),
                                java.util.List.of(), false, null, "SUCCESS", "SUCCESS");
                    }
                    BankDataEntry first = new BankDataEntry("PHASE2-BANK-" + date,
                            "PHASE2-" + date + "-001", context.bankAccountId(),
                            context.windowStart().plusHours(2), "INCOME", new BigDecimal("11.00"),
                            "CNY", "QA", "123456789012", "phase2 first");
                    BankDataEntry duplicate = new BankDataEntry("PHASE2-BANK-" + date,
                            "PHASE2-" + date + "-001", context.bankAccountId(),
                            context.windowStart().plusHours(2), "INCOME", new BigDecimal("11.00"),
                            "CNY", "QA", "123456789012", "phase2 duplicate");
                    BankDataBalanceEntry balance = new BankDataBalanceEntry("PHASE2-BANK-" + date,
                            context.bankAccountId(), new BigDecimal("1234.00"), "CNY",
                            context.windowEnd().minusMinutes(1));
                    boolean includeBalance = date.equals("20260826");
                    return new BankDataCollection("PHASE2-REQ-" + date + "-P1",
                            java.util.List.of(duplicate, first),
                            includeBalance ? java.util.List.of(balance) : java.util.List.of(), true, "page-2",
                            "SUCCESS", "SUCCESS");
                }
            };
        }
    }

    private long triggerPhase2BankData(String token, Long accountId, String requestId) throws Exception {
        String response = mockMvc.perform(post("/api/bank-data/sync-tasks")
                        .header("Authorization", bearer(token)).header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccountId\":" + accountId + ",\"adapterCode\":\"MOCK_PHASE2\","
                                + "\"windowStart\":\"2026-08-26T12:00:00\","
                                + "\"windowEnd\":\"2026-08-28T06:00:00\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("task").get("id").asLong();
    }

    private long importStatement(String token, Long accountId, String statementNo) throws Exception {
        String response = mockMvc.perform(post("/api/statement-imports")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"qa.json\",\"records\":[{\"statementNo\":\"" + statementNo
                                + "\",\"bankAccountId\":" + accountId
                                + ",\"transactionTime\":\"2026-08-27T09:00:00\",\"direction\":\"INCOME\",\"amount\":\"10.00\",\"currency\":\"CNY\",\"counterpartyName\":\"QA\",\"counterpartyAccount\":\"123456789012\",\"summary\":\"QA\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long batchId = objectMapper.readTree(response).get("data").get("id").asLong();
        return objectMapper.readTree(mockMvc.perform(get("/api/statements").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("data").get("records").get(0).get("id").asLong();
    }

    private long triggerBankData(String token, Long accountId, String requestId) throws Exception {
        String response = mockMvc.perform(post("/api/bank-data/sync-tasks")
                        .header("Authorization", bearer(token)).header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccountId\":" + accountId + ",\"adapterCode\":\"MOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("task").get("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private SysUser createUser(String username, long companyId, long roleId) {
        SysUser user = new SysUser();
        user.setCompanyId(companyId);
        user.setUsername(username);
        user.setEmail(username + "@finflow.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getId(), roleId));
        return user;
    }

    private String transferBody(long payerAccountId, String payeeName, String payeeAccount,
                                String bankCode, String amount, String remark) {
        return "{\"bankCode\":\"" + bankCode + "\",\"payerAccountId\":" + payerAccountId
                + ",\"payeeName\":\"" + payeeName + "\",\"payeeAccount\":\"" + payeeAccount
                + "\",\"payeeBank\":\"" + bankCode + "\",\"amount\":" + amount
                + ",\"remark\":\"" + remark + "\"}";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
