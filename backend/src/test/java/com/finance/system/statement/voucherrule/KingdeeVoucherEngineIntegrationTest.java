package com.finance.system.statement.voucherrule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎端到端（WP-B，dev mock 网关）：APPROVED 流水 → 规则 6（银行手续费）→
 * GL_VOUCHER 草稿保存 → voucher_no/pushStatus 回写 + 审计事件。覆盖：正常推送、
 * 非 APPROVED 拒绝、MANUAL 规则缺人工金额拒绝。
 */
@SpringBootTest
@ActiveProfiles("dev")
class KingdeeVoucherEngineIntegrationTest {

    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private StatementRecordMapper statementRecordMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;
    @Autowired
    private StatementAuditEventMapper auditEventMapper;
    @Autowired
    private KingdeeVoucherEngineService engineService;

    @Test
    void approvedBankFeeStatementPushesGlVoucherDraft() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC");
        StatementRecord statement = insertStatement(company.getId(), account.getId(),
                "EXPENSE", new BigDecimal("345.67"), "银行手续费");
        KingdeeVoucherEngineService.KingdeeVoucherPushResult result =
                engineService.push(statement.getId(), 6, null, 1L);

        assertEquals("PUSHED", result.status());
        assertTrue(result.voucherNo() != null && result.voucherNo().startsWith("GL-MOCK-"),
                "dev mock 网关返回 GL-MOCK- 前缀凭证号");
        assertEquals(6, result.ruleNo());

        StatementRecord pushed = statementRecordMapper.selectById(statement.getId());
        assertEquals("GL_PUSHED", pushed.getPushStatus(), "GL 链路状态口径与出纳单 PUSHED 区分");
        assertEquals(result.voucherNo(), pushed.getVoucherNo());
        assertNotNull(pushed.getPushedAt());

        StatementAuditEvent event = auditEventMapper.selectOne(
                new LambdaQueryWrapper<StatementAuditEvent>()
                        .eq(StatementAuditEvent::getStatementId, statement.getId())
                        .eq(StatementAuditEvent::getAction, "GL_VOUCHER_PUSH")
                        .orderByDesc(StatementAuditEvent::getId)
                        .last("LIMIT 1"));
        assertNotNull(event, "推送必须留 GL_VOUCHER_PUSH 审计事件");
        assertEquals("SUCCEEDED", event.getResult());
    }

    @Test
    void nonApprovedStatementIsRejected() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC");
        StatementRecord statement = insertStatement(company.getId(), account.getId(),
                "EXPENSE", new BigDecimal("20.00"), "银行手续费");
        statementRecordMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, "PENDING")
                .eq(StatementRecord::getId, statement.getId()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engineService.push(statement.getId(), 6, null, 1L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("APPROVED"));
    }

    @Test
    void manualShareRuleRequiresAmounts() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC");
        StatementRecord statement = insertStatement(company.getId(), account.getId(),
                "EXPENSE", new BigDecimal("5600.00"), "扣国地税");

        // 规则 10（社保 8 借方 MANUAL）无人工金额 → 400，不产生推送
        BusinessException ex = assertThrows(BusinessException.class,
                () -> engineService.push(statement.getId(), 10, null, 1L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("人工分录行"));

        StatementRecord after = statementRecordMapper.selectById(statement.getId());
        assertEquals("APPROVED", after.getReviewStatus());
    }

    /**
     * 账户级银行账号维度映射（2026-09-21）：未映射的账户制证时被阻断，并指明处置入口。
     * 这是「维度值跟随流水所属账户」取代全局默认账户后的 fail-closed 口径。
     */
    @Test
    void unmappedAccountBlocksPushWithGuidance() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", null);
        StatementRecord statement = insertStatement(company.getId(), account.getId(),
                "EXPENSE", new BigDecimal("345.67"), "银行手续费");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engineService.push(statement.getId(), 6, null, 1L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("银行账号"));
        assertTrue(ex.getMessage().contains("匹配金蝶账户"), "提示必须指明处置入口");

        StatementRecord after = statementRecordMapper.selectById(statement.getId());
        assertEquals("APPROVED", after.getReviewStatus(), "被阻断的推送不得改动流水状态");
    }

    // ---- fixture：Company 名必须含组织关键词（orgResolver 按名解析），code 唯一随机 ----

    private Company insertCompany(String aliasKeyword) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode("KVR_" + suffix);
        company.setName("北京" + aliasKeyword + "锐创科技有限公司-QA-" + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertAccount(Long companyId, String bankCode) {
        return insertAccount(companyId, bankCode, "11050160520009100036");
    }

    private BankAccount insertAccount(Long companyId, String bankCode, String kingdeeAccountNumber) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode(bankCode);
        account.setAccountName("KVR rule-engine test account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        account.setKingdeeAccountNumber(kingdeeAccountNumber);
        bankAccountMapper.insert(account);
        return account;
    }

    private StatementRecord insertStatement(Long companyId, Long bankAccountId, String direction,
                                            BigDecimal amount, String summary) {
        // statement_record.batch_id NOT NULL + FK → 先建真实导入批次；raw_payload TEXT NOT NULL
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("KVR-BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        batch.setSourceType("MANUAL");
        batch.setStatus("COMPLETED");
        batch.setCreatedBy(null);
        batchMapper.insert(batch);

        StatementRecord statement = new StatementRecord();
        statement.setCompanyId(companyId);
        statement.setBatchId(batch.getId());
        statement.setStatementNo("KVR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        statement.setBankAccountId(bankAccountId);
        statement.setTransactionTime(LocalDateTime.parse("2026-09-16T10:15:00"));
        statement.setDirection(direction);
        statement.setAmount(amount);
        statement.setCurrency("CNY");
        statement.setCounterpartyName("招商银行股份有限公司");
        statement.setRawPayload("{\"qa\":\"kvr rule-engine fixture\"}");
        statement.setSummary(summary);
        statement.setValidationStatus("PASSED");
        statement.setReviewStatus("APPROVED");
        statementRecordMapper.insert(statement);
        return statement;
    }
}
