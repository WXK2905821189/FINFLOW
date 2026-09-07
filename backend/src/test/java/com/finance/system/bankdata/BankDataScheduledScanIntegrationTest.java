package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.dto.BankDataProjectionPageResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.CompanyOptionResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated context for the account-driven scheduled scan and the bank-code adapter fallback
 * (2026-09-07). Registers a REAL-mode test double on purpose: the projection page status is a
 * deployment-wide fact ("any REAL adapter wired → REAL"), so wiring a REAL adapter inside the
 * shared V02 context would flip every projection assertion there from NOT_CONFIGURED to REAL.
 * REAL wiring therefore lives in this isolated context only.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(BankDataScheduledScanIntegrationTest.RealQaAdapterConfiguration.class)
class BankDataScheduledScanIntegrationTest {

    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper taskMapper;
    @Autowired
    private BankDataStatementMapper statementMapper;
    @Autowired
    private BankDataRawMessageMapper rawMessageMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private BankDataSyncService bankDataSyncService;
    @Autowired
    private BankDataQueryService bankDataQueryService;

    @Test
    void scheduledScanIsAccountDrivenAndDedupesPerWindow() {
        Company company = insertCompany("QA-SCAN");
        BankAccount realAccount = insertRealQaAccount(company.getId(), "QA real scheduled account");

        // Two scans in the same T-1 window: the second must reuse the first task (request-id
        // idempotency), so exactly one SCHEDULED task exists for the account.
        bankDataSyncService.triggerScheduledSyncs();
        bankDataSyncService.triggerScheduledSyncs();

        assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, company.getId())
                .eq(BankDataSyncTask::getBankAccountId, realAccount.getId())
                .eq(BankDataSyncTask::getTriggerType, "SCHEDULED")));
    }

    @Test
    void manualSyncWithoutExplicitAdapterFallsBackToAccountBankCode() {
        // UI-triggered syncs send neither adapterCode nor connectionCode: resolution must fall
        // back to the account's own bank code (REAL_QA registered here) instead of failing with
        // a bare "adapter is not available" 400.
        Company company = insertCompany("QA-FB");
        BankAccount realAccount = insertRealQaAccount(company.getId(), "QA real fallback account");

        BankDataSyncTaskDetailResponse detail = bankDataSyncService.triggerForCompany(
                company.getId(), null,
                new BankDataSyncRequest(null, realAccount.getId(), null,
                        LocalDateTime.parse("2026-08-26T00:00:00"),
                        LocalDateTime.parse("2026-08-26T23:59:59")),
                "qa-bankcode-fallback-real-" + UUID.randomUUID(), "MANUAL");
        assertEquals("REAL_QA", detail.task().adapterCode());
    }

    @Test
    void companyOptionsScopeByPermission() {
        Company own = insertCompany("QA-OPT");
        Long adminId = insertUser(own.getId(), "qa-opt-admin", 1L);   // role 1 = ADMIN（V24 已授权跨公司）
        Long plainId = insertUser(own.getId(), "qa-opt-plain", null); // 无角色 → 仅本公司

        List<CompanyOptionResponse> adminView = bankDataQueryService.companyOptions(adminId);
        assertTrue(adminView.size() >= 2, "跨公司权限用户应看到全部 ACTIVE 公司（共享 H2 中已有多个公司）");
        assertTrue(adminView.stream().anyMatch(option -> option.id().equals(own.getId())));

        List<CompanyOptionResponse> plainView = bankDataQueryService.companyOptions(plainId);
        assertEquals(1, plainView.size());
        assertEquals(own.getId(), plainView.get(0).id());
    }

    @Test
    void crossCompanyFilterRequiresPermissionAndRowsCarryCompanyName() {
        Company own = insertCompany("QA-XC");
        Company other = insertCompany("QA-XC-OTHER");
        BankAccount otherAccount = insertRealQaAccount(other.getId(), "QA other company account");
        Long adminId = insertUser(own.getId(), "qa-xc-admin", 1L);
        Long plainId = insertUser(own.getId(), "qa-xc-plain", null);
        insertRealQaStatement(other.getId(), otherAccount.getId());

        // 无权限显式指定公司 → 403（防线：不能靠传参越权）
        BusinessException denied = assertThrows(BusinessException.class,
                () -> bankDataQueryService.queryProjection(plainId, "statements", 1, 20, null,
                        null, null, null, null, null, null, null, other.getId()));
        assertEquals(403, denied.getCode());

        // 有权限 + 指定公司 → 只见该公司行，且行带公司名
        BankDataProjectionPageResponse<?> scoped = bankDataQueryService.queryProjection(adminId, "statements", 1, 20,
                null, null, null, null, null, null, null, null, other.getId());
        assertEquals(1, scoped.total());
        assertTrue(scoped.records().stream().allMatch(row -> other.getName().equals(((BankDataStatementResponse) row).companyName())));

        // 有权限 + 不指定公司 → 全部 ACTIVE 公司可见
        BankDataProjectionPageResponse<?> all = bankDataQueryService.queryProjection(adminId, "statements", 1, 20,
                null, null, null, null, null, null, null, null, null);
        assertTrue(all.total() >= 1);
        assertTrue(all.records().stream().anyMatch(row -> other.getName().equals(((BankDataStatementResponse) row).companyName())));

        // 不存在的公司 → 404
        BusinessException missing = assertThrows(BusinessException.class,
                () -> bankDataQueryService.queryProjection(adminId, "statements", 1, 20, null,
                        null, null, null, null, null, null, null, 9_999_999L));
        assertEquals(404, missing.getCode());
    }

    private Long insertUser(Long companyId, String username, Long roleId) {
        SysUser user = new SysUser();
        user.setCompanyId(companyId);
        user.setUsername(username + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        user.setPasswordHash("$2a$10$qa-test-only-not-a-real-hash-not-used-for-login-in-this-class");
        user.setEmail(username + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@qa.finflow.local");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        if (roleId != null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
        return user.getId();
    }

    /** 在 REAL_QA 上下文中造一条真实链路数据：任务 + 原始报文 + 流水行，供投影查询断言。 */
    private void insertRealQaStatement(Long companyId, Long bankAccountId) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-XC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setAdapterCode("REAL_QA");
        task.setBankAccountId(bankAccountId);
        task.setRequestId("qa-xc-" + UUID.randomUUID());
        task.setStatus("SUCCEEDED");
        task.setTriggerType("MANUAL");
        task.setWindowStart(LocalDateTime.parse("2026-08-26T00:00:00"));
        task.setWindowEnd(LocalDateTime.parse("2026-08-26T23:59:59"));
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("REAL_QA");
        raw.setContentSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        raw.setPayload("{\"qa\":\"cross-company fixture\"}");
        raw.setRetentionUntil(LocalDateTime.now().plusDays(30));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(companyId);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(bankAccountId);
        statement.setStatementNo("XC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        statement.setTransactionTime(LocalDateTime.parse("2026-08-26T10:00:00"));
        statement.setDirection("EXPENSE");
        statement.setAmount(new BigDecimal("123.45"));
        statement.setValidationStatus("PENDING");
        statementMapper.insert(statement);
    }

    private Company insertCompany(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode(prefix + "_" + suffix);
        company.setName(prefix + " company " + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertRealQaAccount(Long companyId, String accountName) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("REAL_QA");
        account.setAccountName(accountName);
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealQaAdapterConfiguration {

        @Bean
        BankDataAdapter realQaAdapter() {
            // REAL-mode test double: real calls stay gated (real-adapters-enabled=false →
            // terminal UNKNOWN), which still persists the task — exactly what the assertions need.
            return new BankDataAdapter() {
                @Override
                public String adapterCode() {
                    return "REAL_QA";
                }

                @Override
                public BankAdapterExecutionMode executionMode() {
                    return BankAdapterExecutionMode.REAL;
                }

                @Override
                public BankDataCollection collect(BankDataSyncContext context) {
                    return new BankDataCollection("REAL-QA-RAW", java.util.List.of(),
                            java.util.List.of(), false, null, "SUCCESS", "SUCCESS");
                }
            };
        }
    }
}
