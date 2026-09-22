package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.statement.dto.PushBatchResult;
import com.finance.system.statement.dto.PushRowResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W16-A1 一键推送至金蝶——规则编排离线集成测试（2026-09-22，阶段 0.2）。
 *
 * <p>AI 制证退役后的唯一制证编排，分支口径全部锁死（dev mock 网关 + V34 种子规则）：</p>
 * <ul>
 *   <li>AUTO_FILL 且无需人工金额 → 复核内化（自动 APPROVED + AUTO_PUSH_REVIEW 审计）→ 自动推送 GL；</li>
 *   <li>重跑幂等：已 PUSHED/GL_PUSHED 是终局 → ALREADY_PUSHED，不重复推送；</li>
 *   <li>唯一命中含 MANUAL 行 → PROBLEM_MANUAL_AMOUNT；双命中 → PROBLEM_CANDIDATES；
 *       无命中 → PROBLEM_UNMATCHED；账户未映射金蝶档案 → PROBLEM_PUSH_FAILED；
 *       校验未通过 → PROBLEM_ELIGIBLE；</li>
 *   <li>跳过：纯人工制证账户（SKIPPED_MANUAL，且不产生标准流水）、已人工驳回（SKIPPED）、
 *       越权（SKIPPED）；</li>
 *   <li>撤回态复活（V39 语义）：WITHDRAWN → 复位待复核 → 内化 → 推送成功。</li>
 * </ul>
 *
 * <p>种子规则对应关系（V34）：规则 6 支付银行手续费（scope 300,400 / CITIC / EXPENSE /
 * SUMMARY CONTAINS 手续费，无 MANUAL 行）；规则 10 缴纳社保（scope 300,400,900，8 条 MANUAL 借方）；
 * 规则 11 缴纳个税（scope 仅 300，与 10 同附言「扣国地税」构成冲突对）；公司名含「雪云」→ org 400、
 * 含「即设」→ org 300（KingdeeOrgResolver 内置别名）。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class BankDataPushServiceIntegrationTest {

    /** mock 账套科目目录里挂银行账号维度的档案编码（与既有引擎测试同款）。 */
    private static final String MAPPED_BANK_ACCOUNT = "11050160520009100036";

    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper taskMapper;
    @Autowired
    private BankDataRawMessageMapper rawMessageMapper;
    @Autowired
    private BankDataStatementMapper bankDataStatementMapper;
    @Autowired
    private StatementRecordMapper statementRecordMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;
    @Autowired
    private StatementAuditEventMapper auditEventMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private BankDataPushService pushService;

    // ---- 核心链路：AUTO_FILL → 复核内化 → 自动推送 ----

    @Test
    void autoFillRowIsApprovedInternalizedAndPushedToGl() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        Long operatorId = insertUser(company.getId(), "bps-happy", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.totalCount());
        assertEquals(1, result.pushedCount(), "唯一命中规则 6 且无 MANUAL 行 → 自动推送");
        assertEquals(0, result.problemCount());
        assertEquals(0, result.skippedCount());
        assertEquals(0, result.alreadyCount());
        assertNotNull(result.batchNo(), "批次号来自转入链路");

        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PUSHED", rowResult.outcome());
        assertEquals(6, rowResult.ruleNo(), "命中种子规则 6（支付银行手续费）");
        assertTrue(rowResult.voucherNo() != null && rowResult.voucherNo().startsWith("GL-MOCK-"),
                "dev mock 网关返回 GL-MOCK- 前缀凭证号，实际：" + rowResult.voucherNo());
        assertEquals("GL_PUSHED", rowResult.pushStatus());

        // 写后断言：标准流水被复核内化 + 推送落库（§15.3 假成功纪律）
        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("APPROVED", record.getReviewStatus(), "PENDING 必须被复核内化为 APPROVED");
        assertEquals(operatorId, record.getReviewedBy());
        assertNotNull(record.getReviewedAt());
        assertTrue(record.getReviewComment() != null && record.getReviewComment().contains("一键推送至金蝶"),
                "内化必须留下可追溯的复核意见：" + record.getReviewComment());
        assertEquals("GL_PUSHED", record.getPushStatus());
        assertEquals(rowResult.voucherNo(), record.getVoucherNo());

        assertAudit(record.getId(), "AUTO_PUSH_REVIEW", "SUCCESS");
        assertAudit(record.getId(), "GL_VOUCHER_PUSH", "SUCCEEDED");
    }

    @Test
    void rerunAfterPushIsIdempotentAlreadyPushed() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        Long operatorId = insertUser(company.getId(), "bps-idem", 1L);

        PushBatchResult first = pushService.pushToKingdee(List.of(row.getId()), operatorId);
        assertEquals(1, first.pushedCount());
        String voucherNo = first.rows().get(0).voucherNo();

        PushBatchResult second = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, second.alreadyCount(), "重跑已推送行必须幂等跳过（终局不动）");
        assertEquals(0, second.pushedCount());
        assertEquals(0, second.problemCount());
        PushRowResult rowResult = second.rows().get(0);
        assertEquals("ALREADY_PUSHED", rowResult.outcome());
        assertEquals(voucherNo, rowResult.voucherNo(), "幂等：凭证号不变，不得重复推送");

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("GL_PUSHED", record.getPushStatus());
        assertEquals(voucherNo, record.getVoucherNo());
        Long glPushAudits = auditEventMapper.selectCount(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, record.getId())
                .eq(StatementAuditEvent::getAction, "GL_VOUCHER_PUSH"));
        assertEquals(1L, glPushAudits, "重跑不得产生第二次 GL 推送审计（防金蝶重复凭证）");
    }

    // ---- 问题凭证分支 ----

    @Test
    void manualAmountRuleFallsToProblemManualAmount() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "bps-manual", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.problemCount());
        assertEquals(0, result.pushedCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PROBLEM_MANUAL_AMOUNT", rowResult.outcome(),
                "雪云(400) 对「扣国地税」唯一命中规则 10（社保，8 条 MANUAL 借方）→ 需人工补金额");
        assertEquals(10, rowResult.ruleNo());
        assertTrue(rowResult.message().contains("人工分摊行"), rowResult.message());

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("APPROVED", record.getReviewStatus(), "复核内化发生在规则匹配之前，状态已内化");
        assertEquals("NOT_PUSHED", record.getPushStatus(), "未推送成功，推送状态不得改变");
        assertNull(record.getVoucherNo());
    }

    @Test
    void conflictingRulesFallToProblemCandidates() {
        Company company = insertCompany("即设");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "bps-cand", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.problemCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PROBLEM_CANDIDATES", rowResult.outcome(),
                "即设(300) 对「扣国地税」同时命中规则 10（社保）与规则 11（个税）→ 双候选");
        assertTrue(rowResult.message().contains("2 条"), "消息必须带候选数：" + rowResult.message());
        assertNull(rowResult.ruleNo(), "双候选不得替人定规则");

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("APPROVED", record.getReviewStatus());
        assertEquals("NOT_PUSHED", record.getPushStatus());
    }

    @Test
    void unmatchedSummaryFallsToProblemUnmatched() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "bps-unmatched", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.problemCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PROBLEM_UNMATCHED", rowResult.outcome());
        assertTrue(rowResult.message().contains("无命中规则"), rowResult.message());
    }

    @Test
    void unmappedBankAccountFallsToProblemPushFailed() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", null);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        Long operatorId = insertUser(company.getId(), "bps-pushfail", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.problemCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PROBLEM_PUSH_FAILED", rowResult.outcome(),
                "规则 6 贷方 1002 挂 BANK_ACCOUNT 维度，账户未映射金蝶档案 → 推送被引擎阻断");
        assertEquals(6, rowResult.ruleNo());
        assertTrue(rowResult.message().contains("银行账号"), "阻断原因必须可执行：" + rowResult.message());

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("APPROVED", record.getReviewStatus());
        assertNull(record.getVoucherNo(), "推送失败不得产生凭证号");
    }

    @Test
    void validationFailedRecordFallsToProblemEligible() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        insertStandardRecord(company.getId(), account.getId(), row.getStatementNo(),
                "FAILED", "PENDING", "NOT_PUSHED", null, null);
        Long operatorId = insertUser(company.getId(), "bps-invalid", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.problemCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PROBLEM_ELIGIBLE", rowResult.outcome());
        assertTrue(rowResult.message().contains("流水校验未通过"), rowResult.message());
    }

    // ---- 跳过分支 ----

    @Test
    void rejectedRecordIsSkipped() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        insertStandardRecord(company.getId(), account.getId(), row.getStatementNo(),
                "PASSED", "REJECTED", "NOT_PUSHED", null, null);
        Long operatorId = insertUser(company.getId(), "bps-rejected", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.skippedCount(), "已人工驳回的流水必须尊重驳回结果（SKIPPED）");
        assertEquals(0, result.problemCount());
        assertEquals(0, result.pushedCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("SKIPPED", rowResult.outcome());
        assertTrue(rowResult.message().contains("人工驳回"), rowResult.message());

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("REJECTED", record.getReviewStatus(), "驳回态不得被推送链路改动");
        assertEquals("NOT_PUSHED", record.getPushStatus());
    }

    @Test
    void manualModeAccountIsSkippedBeforeAnything() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "MANUAL", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        Long operatorId = insertUser(company.getId(), "bps-manualmode", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.skippedCount());
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("SKIPPED_MANUAL", rowResult.outcome());
        assertTrue(rowResult.message().contains("纯人工制证"), rowResult.message());

        Long records = statementRecordMapper.selectCount(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertEquals(0L, records, "MANUAL 账户的流水不转入标准流水（数据仅留在系统）");
    }

    @Test
    void crossCompanyRowWithoutPermissionIsSkipped() {
        Company rowCompany = insertCompany("雪云");
        Company ownCompany = insertCompany("即设");
        BankAccount account = insertAccount(rowCompany.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(rowCompany.getId(), account.getId(), "银行手续费");
        Long plainOperatorId = insertUser(ownCompany.getId(), "bps-norole", null);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), plainOperatorId);

        assertEquals(1, result.skippedCount(), "无 cross-company 权限的他公司流水必须跳过");
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("SKIPPED", rowResult.outcome());
        assertTrue(rowResult.message().contains("权限"), rowResult.message());
    }

    // ---- 撤回复活（V39 语义）----

    @Test
    void withdrawnRecordRevivesThenPushes() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "银行手续费");
        Long operatorId = insertUser(company.getId(), "bps-withdrawn", 1L);
        insertStandardRecord(company.getId(), account.getId(), row.getStatementNo(),
                "PASSED", "WITHDRAWN", "NOT_PUSHED", LocalDateTime.now(), operatorId);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);

        assertEquals(1, result.pushedCount(), "撤回态先复活再按当前规则表制证");
        PushRowResult rowResult = result.rows().get(0);
        assertEquals("PUSHED", rowResult.outcome());
        assertTrue(rowResult.voucherNo().startsWith("GL-MOCK-"));

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("APPROVED", record.getReviewStatus());
        assertEquals("GL_PUSHED", record.getPushStatus());
        assertNull(record.getWithdrawnAt(), "复活必须清掉撤回痕迹");
        assertNull(record.getWithdrawnBy());
        assertAudit(record.getId(), "STATEMENT_REVIVE", "SUCCESS");
        assertAudit(record.getId(), "AUTO_PUSH_REVIEW", "SUCCESS");
    }

    // ---- 入参校验 ----

    @Test
    void emptySelectionIsRejected() {
        Company company = insertCompany("雪云");
        Long operatorId = insertUser(company.getId(), "bps-empty", 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pushService.pushToKingdee(List.of(), operatorId));
        assertEquals(400, ex.getCode());
    }

    @Test
    void missingRowIsRejected() {
        Company company = insertCompany("雪云");
        Long operatorId = insertUser(company.getId(), "bps-missing", 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pushService.pushToKingdee(List.of(987654321L), operatorId));
        assertEquals(404, ex.getCode());
    }

    // ---- fixture ----

    /** 公司名必须含组织关键词（orgResolver 按名解析 org 300/400），code 唯一随机。 */
    private Company insertCompany(String aliasKeyword) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode("BPS_" + suffix);
        company.setName("北京" + aliasKeyword + "锐创科技有限公司-QA-" + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private BankAccount insertAccount(Long companyId, String bankCode, String accountingMode,
                                      String kingdeeAccountNumber) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode(bankCode);
        account.setAccountName("BPS push test account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        account.setAccountingMode(accountingMode);
        account.setKingdeeAccountNumber(kingdeeAccountNumber);
        bankAccountMapper.insert(account);
        return account;
    }

    /** 造一条真实链路银行流水：任务 + 原始报文 + 流水行（EXPENSE 345.67，账期 2026-08 无结账记录）。 */
    private BankDataStatement insertBankRow(Long companyId, Long bankAccountId, String summary) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-BPS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setAdapterCode("CITIC");
        task.setBankAccountId(bankAccountId);
        task.setRequestId("qa-bps-" + UUID.randomUUID());
        task.setStatus("SUCCEEDED");
        task.setTriggerType("MANUAL");
        task.setWindowStart(LocalDateTime.parse("2026-08-26T00:00:00"));
        task.setWindowEnd(LocalDateTime.parse("2026-08-26T23:59:59"));
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CITIC");
        raw.setContentSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        raw.setPayload("{\"qa\":\"bank-push fixture\"}");
        raw.setRetentionUntil(LocalDateTime.now().plusDays(30));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(companyId);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(bankAccountId);
        statement.setStatementNo("BPS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        statement.setTransactionTime(LocalDateTime.parse("2026-08-26T10:00:00"));
        statement.setDirection("EXPENSE");
        statement.setAmount(new BigDecimal("345.67"));
        statement.setCurrency("CNY");
        statement.setCounterpartyName("第三方数据服务有限公司");
        statement.setCounterpartyAccountMasked("****8888");
        statement.setSummary(summary);
        statement.setValidationStatus("PENDING");
        bankDataStatementMapper.insert(statement);
        return statement;
    }

    /**
     * 预置一条标准流水（转入链路会按 companyId+statementNo 幂等去重，不会覆盖它）：
     * 用于构造 REJECTED / FAILED / WITHDRAWN 等转入链路造不出的中间态。
     */
    private void insertStandardRecord(Long companyId, Long bankAccountId, String statementNo,
                                      String validationStatus, String reviewStatus, String pushStatus,
                                      LocalDateTime withdrawnAt, Long withdrawnBy) {
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("BPS-BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        batch.setSourceType("BANKDATA");
        batch.setStatus("COMPLETED");
        batch.setCreatedBy(null);
        batchMapper.insert(batch);

        StatementRecord record = new StatementRecord();
        record.setCompanyId(companyId);
        record.setBatchId(batch.getId());
        record.setStatementNo(statementNo);
        record.setBankAccountId(bankAccountId);
        record.setTransactionTime(LocalDateTime.parse("2026-08-26T10:00:00"));
        record.setDirection("EXPENSE");
        record.setAmount(new BigDecimal("345.67"));
        record.setCurrency("CNY");
        record.setCounterpartyName("第三方数据服务有限公司");
        record.setRawPayload("{\"qa\":\"bank-push standard fixture\"}");
        record.setSummary("银行手续费");
        record.setValidationStatus(validationStatus);
        record.setReviewStatus(reviewStatus);
        record.setPushStatus(pushStatus);
        if (withdrawnAt != null) {
            record.setWithdrawnAt(withdrawnAt);
            record.setWithdrawnBy(withdrawnBy);
        }
        statementRecordMapper.insert(record);
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

    private StatementRecord recordByRow(Long companyId, String statementNo) {
        return statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, companyId)
                .eq(StatementRecord::getStatementNo, statementNo)
                .last("LIMIT 1"));
    }

    private void assertAudit(Long statementId, String action, String expectedResult) {
        StatementAuditEvent event = auditEventMapper.selectOne(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, statementId)
                .eq(StatementAuditEvent::getAction, action)
                .orderByDesc(StatementAuditEvent::getId)
                .last("LIMIT 1"));
        assertNotNull(event, "必须留下 " + action + " 审计事件");
        assertEquals(expectedResult, event.getResult(),
                action + " 审计结果不符：" + event.getResult());
    }
}
