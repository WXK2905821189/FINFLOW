package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.finance.system.statement.dto.AiVoucherBatchResponse;
import com.finance.system.statement.dto.AiVoucherRowResult;
import com.finance.system.statement.dto.StatementBatchOpRequest;
import com.finance.system.statement.dto.StatementBatchOpResponse;
import com.finance.system.statement.dto.StatementBatchOpRowResult;
import com.finance.system.statement.dto.StatementBatchPushRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 * 一键 AI 制证（2026-09-16）：转入（幂等）→ AI 建议降级 → 复核闸门内化 → 推送金蝶（测试环境
 * 为 mock 网关）。覆盖：正常链路、纯人工制证账户跳过、重复调用幂等、人工驳回结果被尊重。
 *
 * <p><b>显式指定出纳单落点</b>：2026-09-21 起 {@code kingdee.voucher-target} 默认 {@code GL}
 * （总账凭证）——本类断言的是**收付款单链路**（KD-MOCK-* 凭证号、pushStatus=PUSHED），
 * 故在此固定为 {@code BILL}；总账落点的覆盖见 {@code AiGlVoucherRoutingIntegrationTest}。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "kingdee.voucher-target=BILL")
class BankDataAccountingIntegrationTest {

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
    private StatementAuditEventMapper auditEventMapper;
    @Autowired
    private BankDataAccountingService accountingService;
    @Autowired
    private StatementService statementService;

    @Test
    void aiVoucherHappyPathTransfersReviewsAndPushes() {
        Company company = insertCompany("AIV-HAPPY");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方甲", "货款收入");
        Long adminId = insertUser(company.getId(), "aiv-happy-admin", 1L);

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(row.getId()), adminId);

        assertEquals(1, response.pushedCount());
        assertEquals(0, response.failedCount());
        AiVoucherRowResult result = response.rows().get(0);
        assertEquals("PUSHED", result.outcome());
        assertEquals("PUSHED", result.pushStatus());
        assertNotNull(result.voucherNo());
        assertTrue(result.voucherNo().startsWith("KD-MOCK-"), "dev 配置应走 mock 金蝶网关");

        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertNotNull(record);
        assertEquals("APPROVED", record.getReviewStatus(), "一键链路应自动通过复核（金蝶侧人工审核）");
        assertEquals("PUSHED", record.getPushStatus());

        StatementAuditEvent reviewEvent = auditEventMapper.selectOne(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, record.getId())
                .eq(StatementAuditEvent::getAction, "AI_VOUCHER_REVIEW"));
        assertNotNull(reviewEvent, "复核闸门内化必须留审计事件");
        assertEquals("SUCCESS", reviewEvent.getResult());
    }

    @Test
    void aiVoucherDegradesWhenAiUnavailableButStillPushes() {
        Company company = insertCompany("AIV-NOAI");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方乙", "测试费");
        Long adminId = insertUser(company.getId(), "aiv-noai-admin", 1L);

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(row.getId()), adminId);

        AiVoucherRowResult result = response.rows().get(0);
        assertEquals("PUSHED", result.outcome(), "AI 不可用必须降级继续推送（金蝶人工审核兜底）");
        assertEquals("UNAVAILABLE", result.aiStatus());
        assertNull(result.aiBusinessCategory());
        assertTrue(result.message() != null && result.message().contains("AI 建议不可用"));
    }

    @Test
    void manualModeAccountIsSkippedWithoutTransfer() {
        Company company = insertCompany("AIV-MANUAL");
        BankAccount account = insertAccount(company.getId(), "MANUAL");
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方丙", "线下往来款");
        Long adminId = insertUser(company.getId(), "aiv-manual-admin", 1L);

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(row.getId()), adminId);

        assertEquals(1, response.skippedCount());
        AiVoucherRowResult result = response.rows().get(0);
        assertEquals("SKIPPED_MANUAL", result.outcome());
        assertTrue(result.message() != null && result.message().contains("纯人工制证"),
                "跳过说明应注明纯人工制证模式");

        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertNull(record, "纯人工制证账户不应产生标准流水/推送任何数据");
    }

    @Test
    void secondCallIsIdempotentAlreadyPushed() {
        Company company = insertCompany("AIV-IDEM");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方丁", "服务费");
        Long adminId = insertUser(company.getId(), "aiv-idem-admin", 1L);

        accountingService.createVouchers(List.of(row.getId()), adminId);
        AiVoucherBatchResponse second = accountingService.createVouchers(List.of(row.getId()), adminId);

        assertEquals(0, second.pushedCount());
        assertEquals(1, second.alreadyCount());
        assertEquals("ALREADY_PUSHED", second.rows().get(0).outcome());
    }

    @Test
    void rejectedRecordIsRespectedAndNotPushed() {
        Company company = insertCompany("AIV-REJ");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方戊", "存疑款项");
        Long adminId = insertUser(company.getId(), "aiv-rej-admin", 1L);

        // 先转入，再人工驳回，然后一键制证必须尊重驳回结果。
        accountingService.createVouchers(List.of(row.getId()), adminId);
        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        statementRecordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getPushStatus, "NOT_PUSHED")
                .set(StatementRecord::getReviewStatus, "REJECTED")
                .set(StatementRecord::getReviewComment, "人工驳回：存疑")
                .eq(StatementRecord::getId, record.getId()));

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(row.getId()), adminId);

        AiVoucherRowResult result = response.rows().get(0);
        assertEquals("SKIPPED_REJECTED", result.outcome());
        assertEquals(1, response.skippedCount());
    }

    @Test
    void crossCompanyRowWithoutPermissionFails() {
        Company own = insertCompany("AIV-OWN");
        Company other = insertCompany("AIV-OTHER");
        BankAccount otherAccount = insertAccount(other.getId(), null);
        BankDataStatement otherRow = insertBankStatement(other.getId(), otherAccount.getId(), "他司对手方", "他司流水");
        Long plainId = insertUser(own.getId(), "aiv-plain", null);

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(otherRow.getId()), plainId);

        assertEquals(1, response.failedCount());
        assertEquals("FAILED", response.rows().get(0).outcome());
    }

    @Test
    void emptySelectionIsRejected() {
        Company company = insertCompany("AIV-EMPTY");
        Long adminId = insertUser(company.getId(), "aiv-empty-admin", 1L);
        BusinessException denied = assertThrows(BusinessException.class,
                () -> accountingService.createVouchers(List.of(), adminId));
        assertEquals(400, denied.getCode());
    }

    @Test
    void draftModeStaysPendingWithAiCommentAndDoesNotPush() {
        Company company = insertCompany("AIV-DRAFT");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方己", "咨询费");
        Long adminId = insertUser(company.getId(), "aiv-draft-admin", 1L);

        AiVoucherBatchResponse response = accountingService.createVouchers(List.of(row.getId()), adminId, "DRAFT");

        assertEquals(1, response.draftCount());
        assertEquals(0, response.pushedCount());
        AiVoucherRowResult result = response.rows().get(0);
        assertEquals("DRAFT_CREATED", result.outcome());

        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertNotNull(record);
        assertEquals("PENDING", record.getReviewStatus(), "DRAFT 模式必须停在待复核草稿");
        assertEquals("NOT_PUSHED", record.getPushStatus(), "DRAFT 模式不得推送金蝶");
        assertTrue(record.getReviewComment() != null && record.getReviewComment().contains("AI 建议"),
                "复核意见应带 AI 建议或不可用标注，实际：" + record.getReviewComment());

        StatementAuditEvent draftEvent = auditEventMapper.selectOne(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, record.getId())
                .eq(StatementAuditEvent::getAction, "AI_VOUCHER_DRAFT"));
        assertNotNull(draftEvent, "草稿生成必须留审计事件");
    }

    @Test
    void invalidModeIsRejected() {
        Company company = insertCompany("AIV-BADMODE");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方庚", "测试");
        Long adminId = insertUser(company.getId(), "aiv-badmode-admin", 1L);
        BusinessException denied = assertThrows(BusinessException.class,
                () -> accountingService.createVouchers(List.of(row.getId()), adminId, "AUTO"));
        assertEquals(400, denied.getCode());
    }

    @Test
    void draftThenSelfReviewThenBatchPushFullPipeline() {
        Company company = insertCompany("AIV-FULL");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方辛", "广告费");
        Long adminId = insertUser(company.getId(), "aiv-full-admin", 1L);

        // 1) AI 生成草稿
        accountingService.createVouchers(List.of(row.getId()), adminId, "DRAFT");
        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));
        assertEquals("PENDING", record.getReviewStatus());

        // 2) 生成人自审（BANKDATA 批次允许）批量通过
        StatementBatchOpResponse review = statementService.batchReview(
                new StatementBatchOpRequest(List.of(record.getId()), "APPROVE", null), adminId);
        assertEquals(1, review.successCount());
        assertEquals("APPROVED", review.rows().get(0).outcome());

        // 3) 批量推送金蝶（dev mock 网关）
        StatementBatchOpResponse push = statementService.batchPush(
                new StatementBatchPushRequest(List.of(record.getId())), adminId);
        assertEquals(1, push.successCount());
        StatementBatchOpRowResult pushRow = push.rows().get(0);
        assertEquals("PUSHED", pushRow.outcome());
        assertTrue(pushRow.voucherNo() != null && pushRow.voucherNo().startsWith("KD-MOCK-"));

        StatementRecord pushed = statementRecordMapper.selectById(record.getId());
        assertEquals("PUSHED", pushed.getPushStatus());
        assertEquals("APPROVED", pushed.getReviewStatus());

        // 4) 幂等：再次批量推送 → ALREADY_PUSHED
        StatementBatchOpResponse again = statementService.batchPush(
                new StatementBatchPushRequest(List.of(record.getId())), adminId);
        assertEquals("ALREADY_PUSHED", again.rows().get(0).outcome());
        assertEquals(1, again.skippedCount());
    }

    @Test
    void batchReviewRejectRequiresCommentAndPendingOnly() {
        Company company = insertCompany("AIV-REJCOM");
        BankAccount account = insertAccount(company.getId(), null);
        BankDataStatement row = insertBankStatement(company.getId(), account.getId(), "对手方壬", "招待费");
        Long adminId = insertUser(company.getId(), "aiv-rejcom-admin", 1L);
        accountingService.createVouchers(List.of(row.getId()), adminId, "DRAFT");
        StatementRecord record = statementRecordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, company.getId())
                .eq(StatementRecord::getStatementNo, row.getStatementNo()));

        // 驳回无意见 → 400
        BusinessException denied = assertThrows(BusinessException.class, () -> statementService.batchReview(
                new StatementBatchOpRequest(List.of(record.getId()), "REJECT", null), adminId));
        assertEquals(400, denied.getCode());

        // 带意见驳回 → REJECTED；再批量推送 → SKIPPED（未通过复核）
        StatementBatchOpResponse rejected = statementService.batchReview(
                new StatementBatchOpRequest(List.of(record.getId()), "REJECT", "金额存疑"), adminId);
        assertEquals("REJECTED", rejected.rows().get(0).outcome());
        StatementBatchOpResponse push = statementService.batchPush(
                new StatementBatchPushRequest(List.of(record.getId())), adminId);
        assertEquals("SKIPPED", push.rows().get(0).outcome());
        assertEquals(1, push.skippedCount());
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

    private BankAccount insertAccount(Long companyId, String accountingMode) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("CMB");
        account.setAccountName("AIV accounting account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        account.setAccountingMode(accountingMode);
        bankAccountMapper.insert(account);
        return account;
    }

    private BankDataStatement insertBankStatement(Long companyId, Long bankAccountId,
                                                  String counterpartyName, String summary) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-AIV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setAdapterCode("CMB");
        task.setBankAccountId(bankAccountId);
        task.setRequestId("qa-aiv-" + UUID.randomUUID());
        task.setStatus("SUCCEEDED");
        task.setTriggerType("MANUAL");
        task.setWindowStart(LocalDateTime.parse("2026-09-15T00:00:00"));
        task.setWindowEnd(LocalDateTime.parse("2026-09-15T23:59:59"));
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CMB");
        raw.setContentSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        raw.setPayload("{\"qa\":\"aiv fixture\"}");
        raw.setRetentionUntil(LocalDateTime.now().plusDays(30));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(companyId);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(bankAccountId);
        statement.setStatementNo("AIV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        statement.setTransactionTime(LocalDateTime.parse("2026-09-15T10:15:00"));
        statement.setDirection("INCOME");
        statement.setAmount(new BigDecimal("1234.56"));
        statement.setCurrency("CNY");
        statement.setCounterpartyName(counterpartyName);
        statement.setCounterpartyAccountMasked("**** **** 8888");
        statement.setSummary(summary);
        statement.setValidationStatus("PASSED");
        statementMapper.insert(statement);
        return statement;
    }
}
