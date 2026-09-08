package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.BankDataQueryService;
import com.finance.system.bankdata.dto.BankDataProjectionPageResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementTransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 银行流水一键转入标准流水（2026-09-08）：批次/标准流水按银行流水行的公司归属落库，
 * 幂等走既有 statementNo 去重，跨公司需要 bankdata:cross-company:view。
 */
@SpringBootTest
@ActiveProfiles("dev")
class StatementTransferIntegrationTest {

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
    private StatementRecordMapper statementRecordMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private StatementService statementService;
    @Autowired
    private BankDataQueryService bankDataQueryService;

    @Test
    void transferHappyPathFillsCompanyFromRowAndAppliesFallbacks() {
        Company company = insertCompany("TR-HAPPY");
        BankAccount account = insertAccount(company.getId());
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), null, null);

        Long adminId = insertUser(company.getId(), "tr-happy-admin", 1L);
        StatementImportBatchResponse response = statementService.transferFromBankData(
                new StatementTransferRequest(List.of(row.getId())), adminId);

        assertEquals(1, response.importedCount());
        assertEquals(0, response.duplicateCount());
        assertEquals("BANKDATA", response.sourceType());

        StatementRecord transferred = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getBatchId, batchIdOf(response))
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertEquals(company.getId(), transferred.getCompanyId());
        assertEquals(account.getId(), transferred.getBankAccountId());
        assertEquals("银行交易", transferred.getCounterpartyName(), "对手方名称空时应兜底为「银行交易」");
        assertTrue(transferred.getSummary() != null && !transferred.getSummary().isBlank(), "摘要空时应按银行字段链兜底");
        assertEquals("EXPENSE", transferred.getDirection());
        assertEquals("CNY", transferred.getCurrency(), "币种空时归一为 CNY");
    }

    @Test
    void transferSecondTimeCountsAsDuplicate() {
        Company company = insertCompany("TR-DUP");
        BankAccount account = insertAccount(company.getId());
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方甲", "代发工资");
        Long adminId = insertUser(company.getId(), "tr-dup-admin", 1L);

        statementService.transferFromBankData(new StatementTransferRequest(List.of(row.getId())), adminId);
        StatementImportBatchResponse second = statementService.transferFromBankData(
                new StatementTransferRequest(List.of(row.getId())), adminId);

        assertEquals(0, second.importedCount());
        assertEquals(1, second.duplicateCount(), "重复转入应计入 duplicate，不产生重复标准流水");
    }

    @Test
    void transferRejectsMixedCompanies() {
        Company companyA = insertCompany("TR-MIXA");
        Company companyB = insertCompany("TR-MIXB");
        BankDataStatement rowA = insertBankStatement(companyA.getId(), insertAccount(companyA.getId()).getId(), "甲", "跨公司混合");
        BankDataStatement rowB = insertBankStatement(companyB.getId(), insertAccount(companyB.getId()).getId(), "乙", "跨公司混合");
        Long adminId = insertUser(companyA.getId(), "tr-mix-admin", 1L);

        BusinessException denied = assertThrows(BusinessException.class, () -> statementService.transferFromBankData(
                new StatementTransferRequest(List.of(rowA.getId(), rowB.getId())), adminId));
        assertEquals(400, denied.getCode());
    }

    @Test
    void transferCrossCompanyRequiresPermissionButAdminMayTransfer() {
        Company other = insertCompany("TR-XC-OTHER");
        BankAccount otherAccount = insertAccount(other.getId());
        BankDataStatement otherRow = insertBankStatement(other.getId(), otherAccount.getId(), "外部对手方", "他公司流水");
        Company own = insertCompany("TR-XC-OWN");
        Long plainId = insertUser(own.getId(), "tr-xc-plain", null);
        Long adminId = insertUser(own.getId(), "tr-xc-admin", 1L);

        BusinessException denied = assertThrows(BusinessException.class, () -> statementService.transferFromBankData(
                new StatementTransferRequest(List.of(otherRow.getId())), plainId));
        assertEquals(403, denied.getCode());

        StatementImportBatchResponse response = statementService.transferFromBankData(
                new StatementTransferRequest(List.of(otherRow.getId())), adminId);
        assertEquals(1, response.importedCount());
        StatementImportBatch batch = batchMapper.selectById(batchIdOf(response));
        assertEquals(other.getId(), batch.getCompanyId(), "批次应按银行流水行的公司归属落库，而非操作者本公司");
    }

    // projectionMarksTransferredRows 在 BankDataScheduledScanIntegrationTest：
    // transferred 标记走投影查询，需要 REAL 适配器装配（部署级判定）才有数据行。

    @Test
    void transferRejectsEmptySelection() {
        Company company = insertCompany("TR-EMPTY");
        Long adminId = insertUser(company.getId(), "tr-empty-admin", 1L);
        BusinessException denied = assertThrows(BusinessException.class, () -> statementService.transferFromBankData(
                new StatementTransferRequest(List.of()), adminId));
        assertEquals(400, denied.getCode());
    }

    private Long batchIdOf(StatementImportBatchResponse response) {
        return batchMapper.selectOne(new LambdaQueryWrapper<StatementImportBatch>()
                .eq(StatementImportBatch::getBatchNo, response.batchNo())).getId();
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

    private Company insertCompany(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode(prefix + "_" + suffix);
        company.setName(prefix + " company " + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertAccount(Long companyId) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("CMB");
        account.setAccountName("TR transfer account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account;
    }

    /** 造一条真实链路银行流水：任务 + 原始报文 + 流水行；对手方名称/摘要可传 null 验证兜底。 */
    private BankDataStatement insertBankStatement(Long companyId, Long bankAccountId,
                                                  String counterpartyName, String summary) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-TR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setAdapterCode("CMB");
        task.setBankAccountId(bankAccountId);
        task.setRequestId("qa-tr-" + UUID.randomUUID());
        task.setStatus("SUCCEEDED");
        task.setTriggerType("MANUAL");
        task.setWindowStart(LocalDateTime.parse("2026-08-26T00:00:00"));
        task.setWindowEnd(LocalDateTime.parse("2026-08-26T23:59:59"));
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CMB");
        raw.setContentSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        raw.setPayload("{\"qa\":\"transfer fixture\"}");
        raw.setRetentionUntil(LocalDateTime.now().plusDays(30));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(companyId);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(bankAccountId);
        statement.setStatementNo("TR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        statement.setTransactionTime(LocalDateTime.parse("2026-08-26T10:00:00"));
        statement.setDirection("EXPENSE");
        statement.setAmount(new BigDecimal("123.45"));
        statement.setCounterpartyName(counterpartyName);
        statement.setCounterpartyAccountMasked("****8888");
        statement.setSummary(summary);
        statement.setValidationStatus("PENDING");
        statementMapper.insert(statement);
        return statement;
    }
}
