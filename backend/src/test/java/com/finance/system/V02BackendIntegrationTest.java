package com.finance.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.BankDataQueryService;
import com.finance.system.bankdata.BankDataRawRetentionService;
import com.finance.system.bankdata.BankDataRetentionProperties;
import com.finance.system.bankdata.BankDataSyncService;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.common.exception.BusinessException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private BankDataQueryService bankDataQueryService;
    @Autowired
    private BankDataRawRetentionService rawRetentionService;
    @Autowired
    private BankDataRetentionProperties retentionProperties;

    private Company companyB;
    private SysUser userB;
    private BankAccount accountA;
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

        // V15 retired the two CITIC accounts V1 used to seed, so the admin tenant can no longer
        // borrow id 1 - own the row explicitly (V4 backfills sys_user.company_id = 1).
        accountA = new BankAccount();
        accountA.setCompanyId(1L);
        accountA.setBankCode("CITIC");
        accountA.setAccountName("QA admin company account");
        accountA.setAccountNumber("6222" + suffix + "0002");
        accountA.setCurrency("CNY");
        accountA.setAvailableBalance(new BigDecimal("500000.00"));
        accountA.setStatus("ACTIVE");
        bankAccountMapper.insert(accountA);

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

    private Long adminUserId() {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin")).getId();
    }

    @Test
    void feishuMockNotificationIsTenantScopedAndIdempotent() throws Exception {
        String adminToken = login("admin", "Admin@123");
        String connection = mockMvc.perform(post("/api/feishu/connections")
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"QA 飞书模拟连接\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long connectionId = objectMapper.readTree(connection).get("data").get("id").asLong();
        String destination = mockMvc.perform(post("/api/feishu/destinations")
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":" + connectionId + ",\"destinationType\":\"CHAT\",\"destinationKey\":\"qa-chat\",\"displayName\":\"QA 群\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long destinationId = objectMapper.readTree(destination).get("data").get("id").asLong();
        mockMvc.perform(post("/api/feishu/policies").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"SYNC_FAILED\",\"destinationId\":" + destinationId + ",\"enabled\":true}"))
                .andExpect(status().isOk());
        String payload = "{\"eventId\":\"qa-event-1\",\"eventType\":\"SYNC_FAILED\",\"referenceNo\":\"TASK-1\",\"severity\":\"WARN\",\"summary\":\"同步失败，请查看 FINFLOW\",\"destinationId\":" + destinationId + "}";
        mockMvc.perform(post("/api/feishu/notifications").header("Authorization", bearer(adminToken)).header("X-Request-Id", "qa-feishu-request")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.providerMessageId").value(org.hamcrest.Matchers.startsWith("MOCK-FEISHU-qa-event-1")));
        mockMvc.perform(post("/api/feishu/notifications").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eventId").value("qa-event-1"));
        String companyBToken = login(userB.getUsername(), PASSWORD);
        mockMvc.perform(get("/api/feishu/overview").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connections").isEmpty())
                .andExpect(jsonPath("$.data.destinations").isEmpty());
        mockMvc.perform(get("/api/feishu/deliveries").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
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
        long statementAId = importStatement(adminToken, accountA.getId(), sharedStatementNo);
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
        long syncAId = triggerBankData(adminUserId(), accountA.getId(), syncARequestId);
        assertEquals(syncAId, triggerBankData(adminUserId(), accountA.getId(), syncARequestId));
        String syncBRequestId = "QA-BANK-B-" + UUID.randomUUID();
        long syncBId = triggerBankData(userB.getId(), accountB.getId(), syncBRequestId);

        // 模拟/测试数据已下线（2026-09-03 语义改造）：投影只展示真实银行直联数据。
        // 本用例未启用 CMB/CITIC 真实适配器，因此投影一律 NOT_CONFIGURED / total=0；
        // 租户隔离改由下方「跨公司任务详情 404」这一服务边界断言覆盖。
        for (String resource : java.util.List.of("balances", "statements")) {
            mockMvc.perform(get("/api/bank-data/" + resource).param("requestId", syncARequestId)
                            .header("Authorization", bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"))
                    .andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/bank-data/" + resource).param("requestId", syncBRequestId)
                            .header("Authorization", bearer(companyBToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"))
                    .andExpect(jsonPath("$.data.total").value(0));
        }
        // A cross-company task id is a plain 404 at the service boundary.
        BusinessException crossCompany = assertThrows(BusinessException.class,
                () -> bankDataQueryService.getTaskDetail(userB.getId(), syncAId));
        assertEquals(404, crossCompany.getCode());
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
                                // Adapter routing is fail-closed now: an account whose bank has no
                                // registered adapter is rejected, so name this context's double.
                                + ",\"adapterCode\":\"MOCK_PHASE2\""
                                + ",\"windowStart\":\"2026-08-27T00:00:00\",\"windowEnd\":\"2026-08-28T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobNo").isString())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
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
        triggerBankData(adminUserId(), accountA.getId(), adminRequestId);
        triggerBankData(userB.getId(), accountB.getId(), companyBRequestId);

        // 三模块已下线（回单/对账单/代发）：resource 白名单只剩 balances + statements，
        // 其余一律 404「银行侧未开通该功能」。
        for (String resource : java.util.List.of("receipts", "reconciliations", "payroll")) {
            mockMvc.perform(get("/api/bank-data/" + resource)
                            .header("Authorization", bearer(companyBToken))
                            .param("page", "1")
                            .param("size", "10")
                            .param("requestId", companyBRequestId))
                    .andExpect(status().isNotFound());
        }

        // 模拟/测试数据已下线：未启用真实银行适配器时，投影返回 NOT_CONFIGURED，
        // enabled 恒 false，且不带 normalization-failure 痕迹。
        for (String resource : java.util.List.of("balances", "statements")) {
            mockMvc.perform(get("/api/bank-data/" + resource)
                            .header("Authorization", bearer(companyBToken))
                            .param("page", "1")
                            .param("size", "10")
                            .param("requestId", companyBRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"))
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("normalization-failure"))));

            // 跨公司 requestId 同样被拦在「未连接」门控之前：拿不到任何其他公司的数据。
            mockMvc.perform(get("/api/bank-data/" + resource)
                            .header("Authorization", bearer(companyBToken))
                            .param("requestId", adminRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"));
        }

        mockMvc.perform(get("/api/bank-data/payments").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isNotFound());

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
    void rawResponseAndFailureLogSurviveNormalizationRollback() {
        Long userId = userB.getId();
        String requestId = "QA-RAW-FAIL-" + UUID.randomUUID();

        BankDataSyncTaskDetailResponse detail = bankDataSyncService.trigger(userId,
                new BankDataSyncRequest(null, accountB.getId(), "MOCK_FAIL"), requestId);
        long taskId = detail.task().id();
        assertEquals("FAILED", detail.task().status());
        assertEquals("Bank data synchronization failed during internal processing",
                detail.task().errorMessage());

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
    void bankDataPhaseTwoHandlesWindowsPaginationDedupAndRetentionCleanup() {
        Long userId = userB.getId();
        String requestId = "QA-PHASE2-" + UUID.randomUUID();

        BankDataSyncTaskDetailResponse detail = bankDataSyncService.trigger(userId,
                new BankDataSyncRequest(null, accountB.getId(), "MOCK_PHASE2",
                        LocalDateTime.parse("2026-08-26T12:00:00"), LocalDateTime.parse("2026-08-28T06:00:00")),
                requestId);
        long taskId = detail.task().id();
        assertEquals("SUCCEEDED", detail.task().status());
        assertEquals(7, detail.task().rawCount());
        assertEquals(4, detail.task().normalizedCount());
        assertEquals(3, detail.task().duplicateCount());

        assertEquals(taskId, triggerPhase2BankData(userId, accountB.getId(), requestId));
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
    void validationClosingAndAuditModulesAreTenantScopedAndPermissionSeparated() throws Exception {
        String adminToken = login("admin", "Admin@123");
        String companyBToken = login(userB.getUsername(), PASSWORD);

        mockMvc.perform(get("/api/validation/rules").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/validation/rules").header("Authorization", bearer(companyBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"QA-RULE\",\"name\":\"金额校验\",\"ruleType\":\"FIELD\",\"expression\":\"amount > 0\"}"))
                .andExpect(status().isForbidden());

        String rule = mockMvc.perform(post("/api/validation/rules").header("Authorization", bearer(adminToken))
                        .header("X-Request-Id", "QA-RULE-CREATE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"QA-RULE\",\"name\":\"金额校验\",\"ruleType\":\"FIELD\",\"expression\":\"amount > 0\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long ruleId = objectMapper.readTree(rule).get("data").get("id").asLong();
        mockMvc.perform(post("/api/validation/rules/" + ruleId + "/activate").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/closing/periods/2026-08/check").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(post("/api/closing/periods/2026-08/close").header("Authorization", bearer(companyBToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/events").header("Authorization", bearer(adminToken))
                        .param("objectType", "VALIDATION_RULE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void scheduledBankDataSyncReusesRequestIdentityForSameWindow() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setCompanyId(companyB.getId());
        profile.setConnectionCode("QA-PHASE2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        profile.setDisplayName("QA phase2 connection");
        // Scheduled scans route by the profile's provider; point it at this context's test double
        // (the generic MOCK adapter was removed, so "MOCK" would now fail closed).
        profile.setProviderType("MOCK_PHASE2");
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

    private long triggerPhase2BankData(Long userId, Long accountId, String requestId) {
        BankDataSyncTaskDetailResponse detail = bankDataSyncService.trigger(userId,
                new BankDataSyncRequest(null, accountId, "MOCK_PHASE2",
                        LocalDateTime.parse("2026-08-26T12:00:00"), LocalDateTime.parse("2026-08-28T06:00:00")),
                requestId);
        return detail.task().id();
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

    private long triggerBankData(Long userId, Long accountId, String requestId) {
        // The generic MOCK adapter no longer exists (mock-clean workstream): route to this
        // context's own test double, the same way production routes to a registered bank adapter.
        BankDataSyncTaskDetailResponse detail = bankDataSyncService.trigger(userId,
                new BankDataSyncRequest(null, accountId, "MOCK_PHASE2"), requestId);
        assertEquals("SUCCEEDED", detail.task().status());
        return detail.task().id();
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

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
