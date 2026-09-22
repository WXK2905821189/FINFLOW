package com.finance.system.statement;

import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.statement.dto.StatementResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-009（2026-09-21）：凭证中心的推送按 {@code kingdee.voucher-target} 分流。
 *
 * <p>背景：真实账套境内主体均未启用「出纳管理」，凭证中心原先直接调
 * {@code kingdeeGateway.push()}（出纳收付款单）→ 一律失败（线上实证：流水 id=12 审计轨迹
 * {@code PUSH_VOUCHER FAILED} +「AR_RECEIVEBILL: 当前组织未启用出纳」）。落点默认 GL 后，
 * 该按钮改走总账凭证链路（dev profile 下为 mock 网关，返回 {@code GL-MOCK-} 前缀凭证号）。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class StatementServiceGlPushIntegrationTest {

    /** mock 账套科目目录里 1002 挂银行账号维度；1001/6602 无维度。 */
    private static final String MAPPED_BANK_ACCOUNT = "11050160520009100036";

    @Autowired
    private StatementService statementService;
    @Autowired
    private CompanyScopeService companyScope;
    @Autowired
    private StatementRecordMapper statementRecordMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;

    @Test
    void voucherCenterPushGoesToGlWhenTargetIsGl() {
        long companyId = companyScope.companyIdForUser(1L);
        BankAccount account = insertAccount(companyId, MAPPED_BANK_ACCOUNT);
        StatementRecord statement = insertStatement(companyId, account.getId(), """
                {"businessCategory":"往来款","suggestedSummary":"内部划转","confidence":0.9,
                 "entries":[{"summary":"内部划转","subjectCode":"1001","subjectName":"库存现金",
                 "direction":"DEBIT","amount":120.00,"confidence":0.9},
                 {"summary":"内部划转","subjectCode":"6602","subjectName":"管理费用",
                 "direction":"CREDIT","amount":120.00,"confidence":0.9}]}""");

        StatementResponse response = statementService.pushVoucher(statement.getId(), 1L);

        assertEquals("GL_PUSHED", response.pushStatus(),
                "落点 GL 时凭证中心推送必须走总账链路（GL_PUSHED），而非出纳单的 PUSHED");
        assertTrue(response.voucherNo() != null && response.voucherNo().startsWith("GL-MOCK-"),
                "dev mock 网关返回 GL-MOCK- 前缀凭证号，实际：" + response.voucherNo());
    }

    /** 账户未映射金蝶档案时，银行类科目（1002）的推送被阻断，并把原因落到 pushMessage（凭证中心可见）。 */
    @Test
    void unmappedAccountBlocksVoucherCenterPushWithActionableMessage() {
        long companyId = companyScope.companyIdForUser(1L);
        BankAccount account = insertAccount(companyId, null);
        StatementRecord statement = insertStatement(companyId, account.getId(), """
                {"suggestedSummary":"收货款","confidence":0.9,
                 "entries":[{"summary":"收货款","subjectCode":"1002","subjectName":"银行存款",
                 "direction":"DEBIT","amount":50.00,"confidence":0.9},
                 {"summary":"收货款","subjectCode":"1001","subjectName":"库存现金",
                 "direction":"CREDIT","amount":50.00,"confidence":0.9}]}""");

        StatementResponse response = statementService.pushVoucher(statement.getId(), 1L);

        assertEquals("FAILED", response.pushStatus(), "前置校验未通过时落 FAILED（非 GL_FAILED）");
        assertTrue(response.pushMessage() != null && response.pushMessage().contains("尚未映射金蝶银行账号档案编码"),
                "必须把可执行的处置提示带回凭证中心：" + response.pushMessage());
    }

    // ---- P1-6（2026-09-22 真账套端到端测试抓出）----

    /**
     * 总账落点失败（{@code GL_FAILED}）的流水必须能从凭证中心重推。
     *
     * <p>原先 CAS 认领条件只含 {@code NOT_PUSHED}/{@code FAILED}，漏掉 {@code GL_FAILED}
     * ——而总账链路失败写的正是该字面量。结果：**总账推送失败的流水永久无法重推**，
     * 用户看到的是误导性的 409「已在推送中或已完成」，与事实（失败待重试）不符。
     * 这就是 W15「修了根因仍推不上去」的另一半原因（线上实测：流水 18 重推即 409）。</p>
     */
    @Test
    void glFailedStatementCanBeRetriedFromVoucherCenter() {
        long companyId = companyScope.companyIdForUser(1L);
        BankAccount account = insertAccount(companyId, MAPPED_BANK_ACCOUNT);
        StatementRecord statement = insertStatement(companyId, account.getId(), flatSuggestion());
        setPushStatus(statement.getId(), "GL_FAILED");

        StatementResponse response = statementService.pushVoucher(statement.getId(), 1L);

        assertEquals("GL_PUSHED", response.pushStatus(),
                "GL_FAILED 必须可被重推（CAS 认领条件须含该字面量）");
        assertTrue(response.voucherNo() != null && response.voucherNo().startsWith("GL-MOCK-"),
                "重推成功应拿到新的凭证号，实际：" + response.voucherNo());
    }

    /**
     * 已成功推送（{@code GL_PUSHED}）的流水再次点击推送：幂等返回当前态，
     * 不得落到 CAS 被拒成 409「已在推送中或已完成」（语义误导），也不得重复推送。
     */
    @Test
    void glPushedStatementIsIdempotentOnSecondPush() {
        long companyId = companyScope.companyIdForUser(1L);
        BankAccount account = insertAccount(companyId, MAPPED_BANK_ACCOUNT);
        StatementRecord statement = insertStatement(companyId, account.getId(), flatSuggestion());

        StatementResponse first = statementService.pushVoucher(statement.getId(), 1L);
        assertEquals("GL_PUSHED", first.pushStatus());

        StatementResponse second = statementService.pushVoucher(statement.getId(), 1L);

        assertEquals("GL_PUSHED", second.pushStatus(), "重复点击必须幂等返回已推送态");
        assertEquals(first.voucherNo(), second.voucherNo(), "幂等：不得更换凭证号（即不得重复推送）");
    }

    private static String flatSuggestion() {
        return """
                {"businessCategory":"往来款","suggestedSummary":"内部划转","confidence":0.9,
                 "entries":[{"summary":"内部划转","subjectCode":"1001","subjectName":"库存现金",
                 "direction":"DEBIT","amount":120.00,"confidence":0.9},
                 {"summary":"内部划转","subjectCode":"6602","subjectName":"管理费用",
                 "direction":"CREDIT","amount":120.00,"confidence":0.9}]}""";
    }

    private void setPushStatus(Long id, String status) {
        statementRecordMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StatementRecord>()
                        .set(StatementRecord::getPushStatus, status)
                        .eq(StatementRecord::getId, id));
    }

    // ---- fixture ----

    private BankAccount insertAccount(long companyId, String kingdeeAccountNumber) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("CMB");
        account.setAccountName("FIX-009 test account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("1000.00"));
        account.setStatus("ACTIVE");
        account.setAccountingMode("KINGDEE_AUTO");
        account.setKingdeeAccountNumber(kingdeeAccountNumber);
        bankAccountMapper.insert(account);
        return account;
    }

    private StatementRecord insertStatement(long companyId, Long bankAccountId, String aiJson) {
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("F9-BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        batch.setSourceType("MANUAL");
        batch.setStatus("COMPLETED");
        batch.setCreatedBy(null);
        batchMapper.insert(batch);

        StatementRecord statement = new StatementRecord();
        statement.setCompanyId(companyId);
        statement.setBatchId(batch.getId());
        statement.setStatementNo("F9-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        statement.setBankAccountId(bankAccountId);
        statement.setTransactionTime(LocalDateTime.parse("2026-09-21T10:15:00"));
        statement.setDirection("INCOME");
        statement.setAmount(new BigDecimal("120.00"));
        statement.setCurrency("CNY");
        statement.setCounterpartyName("财付通支付科技有限公司");
        statement.setRawPayload("{\"qa\":\"fix-009 fixture\"}");
        statement.setSummary("内部划转");
        statement.setValidationStatus("PASSED");
        statement.setReviewStatus("APPROVED");
        statement.setPushStatus("NOT_PUSHED");
        statement.setAiSuggestionJson(aiJson);
        statementRecordMapper.insert(statement);
        return statement;
    }
}
