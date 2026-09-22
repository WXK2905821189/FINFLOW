package com.finance.system.statement.vouchergroup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.common.api.PageResponse;
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
import com.finance.system.statement.BankDataPushService;
import com.finance.system.statement.dto.PushBatchResult;
import com.finance.system.statement.dto.PushRowResult;
import com.finance.system.statement.voucherrule.dto.VoucherProblemEditDoc;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 问题凭证编辑器端到端（W16-A2，dev mock 网关）：闭环「落桶 → 列表/详情 →
 * 保存重校验 → 提交推送 → 出列/留桶」。
 *
 * <p>锁分支（规划 2.3/2.4）：</p>
 * <ul>
 *   <li>落桶：一键推送 PROBLEM_* 行结果同步写 problem_type/reason（复用 A1 集成测试同款种子）；</li>
 *   <li>列表：problem_type IS NOT NULL 进桶 + 类型筛选 + 未知类型 400；</li>
 *   <li>详情：落桶原因 + 规则预填（UNMATCHED 无预填）；</li>
 *   <li>PUT 校验全分支：借贷不平 400 / 借或贷缺侧 400 / 父科目 400 / 金额非正 400 /
 *       维度值缺类型 400 / 正常保存回读一致 + 审计 PROBLEM_EDIT；</li>
 *   <li>submit：编辑态推送成功 GL_PUSHED 出列（列表消失 + problem_* 清空）；
 *       未保存编辑态且无规则预填 400；失败留桶刷新 reason（mock 网关不失败，
 *       用「已推送后重提」409 覆盖 submit 幂等分支）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("dev")
class VoucherProblemIntegrationTest {

    /** mock 账套科目目录：1002 挂银行账号维度；6603.04 明细 / 6603 父科目（有子 6603.04）。 */
    private static final String MAPPED_BANK_ACCOUNT = "11050160520009100036";
    private static final String DETAIL_ACCOUNT = "6603.04";
    private static final String PARENT_ACCOUNT = "6603";

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
    @Autowired
    private VoucherProblemService problemService;

    // ---- 落桶：一键推送 PROBLEM_* 同步写 statement_record ----

    @Test
    void pushFallsToProblemAndMarksStatementRecord() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-mark", 1L);

        PushBatchResult result = pushService.pushToKingdee(List.of(row.getId()), operatorId);
        assertEquals(1, result.problemCount());
        assertEquals("PROBLEM_MANUAL_AMOUNT", result.rows().get(0).outcome());

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        assertEquals("PROBLEM_MANUAL_AMOUNT", record.getProblemType(),
                "问题行必须同步落桶到 statement_record（A2 列表数据源）");
        assertNotNull(record.getProblemReason());
        assertNotNull(record.getProblemUpdatedAt());
        assertEquals(operatorId, record.getProblemUpdatedBy());
    }

    // ---- 列表：进桶 / 筛选 / 未知类型 ----

    @Test
    void problemListContainsMarkedRowAndFiltersByType() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-list", 1L);

        pushService.pushToKingdee(List.of(row.getId()), operatorId);

        PageResponse<VoucherProblemService.ProblemRowResponse> page =
                problemService.pageProblems(1, 20, "PROBLEM_UNMATCHED", null, operatorId);
        assertTrue(page.records().stream().anyMatch(r -> r.statementNo().equals(row.getStatementNo())),
                "UNMATCHED 筛选必须包含刚落桶的行");
        VoucherProblemService.ProblemRowResponse hit = page.records().stream()
                .filter(r -> r.statementNo().equals(row.getStatementNo())).findFirst().orElseThrow();
        assertEquals("PROBLEM_UNMATCHED", hit.problemType());
        assertTrue(hit.problemReason().contains("无命中规则"), hit.problemReason());

        PageResponse<VoucherProblemService.ProblemRowResponse> otherType =
                problemService.pageProblems(1, 20, "PROBLEM_PUSH_FAILED", null, operatorId);
        assertFalse(otherType.records().stream().anyMatch(r -> r.statementNo().equals(row.getStatementNo())),
                "类型筛选必须互斥");

        // 关键字命中流水号
        PageResponse<VoucherProblemService.ProblemRowResponse> byKeyword =
                problemService.pageProblems(1, 20, null, row.getStatementNo(), operatorId);
        assertEquals(1, byKeyword.records().size());
    }

    @Test
    void unknownProblemTypeFilterIsRejected() {
        Company company = insertCompany("雪云");
        Long operatorId = insertUser(company.getId(), "vps-unknown", 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.pageProblems(1, 20, "PROBLEM_NOPE", null, operatorId));
        assertEquals(400, ex.getCode());
    }

    // ---- 详情：落桶原因 + 规则预填 ----

    @Test
    void problemDetailShowsReasonAndPrefillForManualAmount() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-detail", 1L);
        pushService.pushToKingdee(List.of(row.getId()), operatorId);

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        VoucherProblemService.ProblemDetailResponse detail = problemService.getProblem(record.getId(), operatorId);

        assertEquals("PROBLEM_MANUAL_AMOUNT", detail.row().problemType());
        assertTrue(detail.prefillNeedManualAmount(), "规则 10 含 MANUAL 行，预填必须标记 needManualAmount");
        assertEquals(10, detail.prefillRuleNo());
        assertNotNull(detail.prefillDebitLines());
        assertNull(detail.editDoc(), "尚未保存过编辑态，editDoc 必须为 null");
    }

    @Test
    void unmatchedDetailHasNoPrefill() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-unmatch-detail", 1L);
        pushService.pushToKingdee(List.of(row.getId()), operatorId);

        StatementRecord record = recordByRow(company.getId(), row.getStatementNo());
        VoucherProblemService.ProblemDetailResponse detail = problemService.getProblem(record.getId(), operatorId);
        assertEquals("PROBLEM_UNMATCHED", detail.row().problemType());
        assertNull(detail.prefillDebitLines(), "UNMATCHED 无规则预填");
        assertTrue(detail.row().problemReason().contains("无命中规则"));
    }

    // ---- PUT 保存重校验全分支（规划 2.3）----

    @Test
    void saveEditRejectsUnbalancedEntries() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-unbalanced", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("100.00 借 / 90.00 贷", DETAIL_ACCOUNT,
                new BigDecimal("100.00"), new BigDecimal("90.00"), null);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(), request, operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("借贷不平衡"), ex.getMessage());
    }

    @Test
    void saveEditRejectsMissingSide() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-noside", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = new VoucherProblemEditDoc("只有借方",
                List.of(new VoucherProblemEditDoc.ProblemLine(DETAIL_ACCOUNT, "手续费",
                        null, null, new BigDecimal("100.00"), "MANUAL", null)),
                List.of(), null, null);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(), request, operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("贷方"), ex.getMessage());
    }

    @Test
    void saveEditRejectsParentAccount() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-parent", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("父科目校验", PARENT_ACCOUNT,
                new BigDecimal("100.00"), new BigDecimal("100.00"), null);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(), request, operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("父科目"), ex.getMessage());
    }

    @Test
    void saveEditRejectsNonPositiveAmount() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-amount", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = new VoucherProblemEditDoc("零金额",
                List.of(new VoucherProblemEditDoc.ProblemLine(DETAIL_ACCOUNT, "手续费",
                        null, null, BigDecimal.ZERO, "MANUAL", null)),
                List.of(new VoucherProblemEditDoc.ProblemLine("1002", "银行存款",
                        null, MAPPED_BANK_ACCOUNT, BigDecimal.ZERO, "MANUAL", null)),
                null, null);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(), request, operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("正数"), ex.getMessage());
    }

    @Test
    void saveEditRejectsDimensionValueWithoutType() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-dim", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        // 贷方 1002 挂银行账号维度：给了维度值但 dimension=null（normalizeSide 拦截）
        VoucherProblemEditDoc doc = new VoucherProblemEditDoc("维度缺类型",
                List.of(new VoucherProblemEditDoc.ProblemLine(DETAIL_ACCOUNT, "手续费",
                        null, null, new BigDecimal("50.00"), "MANUAL", null)),
                List.of(new VoucherProblemEditDoc.ProblemLine("1002", "银行存款",
                        null, MAPPED_BANK_ACCOUNT, new BigDecimal("50.00"), "MANUAL", null)),
                null, null);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(), request, operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("维度"), ex.getMessage());
    }

    @Test
    void saveEditPersistsValidDocAndWritesAudit() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-save", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("社保公司部分", DETAIL_ACCOUNT,
                new BigDecimal("100.00"), new BigDecimal("100.00"), MAPPED_BANK_ACCOUNT);
        VoucherProblemService.VoucherProblemEditRequest request =
                new VoucherProblemService.VoucherProblemEditRequest(doc);
        VoucherProblemService.ProblemRowResponse saved =
                problemService.saveEdit(record.getId(), request, operatorId);
        assertNotNull(saved);

        StatementRecord after = statementRecordMapper.selectById(record.getId());
        assertNotNull(after.getProblemEditJson(), "编辑态必须持久化");
        VoucherProblemService.ProblemDetailResponse detail = problemService.getProblem(record.getId(), operatorId);
        assertNotNull(detail.editDoc());
        assertEquals("社保公司部分", detail.editDoc().summary());
        assertEquals(1, detail.editDoc().debitLines().size());
        assertEquals(0, new BigDecimal("100.00").compareTo(detail.editDoc().debitLines().get(0).amount()));
        assertEquals(operatorId, detail.editDoc().editedBy());

        StatementAuditEvent audit = auditEventMapper.selectOne(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, record.getId())
                .eq(StatementAuditEvent::getAction, "PROBLEM_EDIT")
                .last("LIMIT 1"));
        assertNotNull(audit, "保存必须留 PROBLEM_EDIT 审计");
        assertEquals("SUCCEEDED", audit.getResult());
    }

    // ---- submit：出列与幂等（规划 2.4）----

    @Test
    void submitWithEditDocPushesAndClearsProblem() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-submit", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("人工制证", DETAIL_ACCOUNT,
                new BigDecimal("345.67"), new BigDecimal("345.67"), MAPPED_BANK_ACCOUNT);
        problemService.saveEdit(record.getId(),
                new VoucherProblemService.VoucherProblemEditRequest(doc), operatorId);

        VoucherProblemService.SubmitResult result = problemService.submit(record.getId(), operatorId);

        assertEquals("PUSHED", result.status());
        assertTrue(result.voucherNo() != null && result.voucherNo().startsWith("GL-MOCK-"),
                "mock 网关返回 GL-MOCK- 凭证号：" + result.voucherNo());

        StatementRecord after = statementRecordMapper.selectById(record.getId());
        assertEquals("GL_PUSHED", after.getPushStatus(), "修复推送成功必须写 GL_PUSHED");
        assertEquals(result.voucherNo(), after.getVoucherNo());
        assertNull(after.getProblemType(), "出列：problem_type 必须清空");
        assertNull(after.getProblemReason());
        assertNull(after.getProblemEditJson());

        PageResponse<VoucherProblemService.ProblemRowResponse> page =
                problemService.pageProblems(1, 20, null, row.getStatementNo(), operatorId);
        assertEquals(0, page.records().size(), "出列后问题列表必须查不到该行");

        StatementAuditEvent audit = auditEventMapper.selectOne(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, record.getId())
                .eq(StatementAuditEvent::getAction, "PROBLEM_SUBMIT")
                .last("LIMIT 1"));
        assertNotNull(audit, "提交必须留 PROBLEM_SUBMIT 审计");
        assertEquals("SUCCEEDED", audit.getResult());
    }

    @Test
    void submitWithoutEditDocAndNoPrefillIsRejected() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-submit-nop", 1L);
        // markProblemDirectly 的标准流水摘要写死「银行手续费」会命中规则 6（无 MANUAL 行
        // → 有可用预填 → 直接推送成功，见 submitWithPrefillPushesWithoutEditDoc）。
        // 本用例要锁的是「无任何可用分录」分支，故把摘要改成不可能命中的文案。
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());
        statementRecordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getSummary, "量子纠缠对撞测试款")
                .eq(StatementRecord::getId, record.getId()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.submit(record.getId(), operatorId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("无可用分录"), ex.getMessage());

        StatementRecord after = statementRecordMapper.selectById(record.getId());
        assertEquals("PROBLEM_MANUAL_AMOUNT", after.getProblemType(),
                "被拒绝的提交不得出列");
    }

    /**
     * 反向语义（合理功能）：落桶行若按当前规则表已能唯一命中且无 MANUAL 行，
     * 无需人工编辑即可直接提交推送成功（例如规则中心补配了规则后回池处理）。
     */
    @Test
    void submitWithPrefillPushesWithoutEditDoc() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "扣国地税");
        Long operatorId = insertUser(company.getId(), "vps-prefill", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());
        // 雪云(400) 对「扣国地税」唯一命中规则 10（社保，8 条 MANUAL 借方）→ 需要 MANUAL 金额，
        // 无法直接推。换成摘要「银行手续费」命中规则 6（无 MANUAL 行）→ 预填可直接推。
        statementRecordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getSummary, "银行手续费")
                .eq(StatementRecord::getId, record.getId()));

        VoucherProblemService.SubmitResult result = problemService.submit(record.getId(), operatorId);

        assertEquals("PUSHED", result.status());
        StatementRecord after = statementRecordMapper.selectById(record.getId());
        assertEquals("GL_PUSHED", after.getPushStatus());
        assertNull(after.getProblemType(), "预填直接推送成功也必须出列");
    }

    @Test
    void submitAfterPushIsRejected409() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-submit-409", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("首推", DETAIL_ACCOUNT,
                new BigDecimal("345.67"), new BigDecimal("345.67"), MAPPED_BANK_ACCOUNT);
        problemService.saveEdit(record.getId(),
                new VoucherProblemService.VoucherProblemEditRequest(doc), operatorId);
        problemService.submit(record.getId(), operatorId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.submit(record.getId(), operatorId));
        assertEquals(409, ex.getCode(), "已推送成功的行重复提交必须 409");
        assertTrue(ex.getMessage().contains("无需重复提交"), ex.getMessage());
    }

    @Test
    void alreadyPushedRecordCannotBeEdited() {
        Company company = insertCompany("雪云");
        BankAccount account = insertAccount(company.getId(), "CITIC", "KINGDEE_AUTO", MAPPED_BANK_ACCOUNT);
        BankDataStatement row = insertBankRow(company.getId(), account.getId(), "量子纠缠对撞测试款");
        Long operatorId = insertUser(company.getId(), "vps-edit-409", 1L);
        StatementRecord record = markProblemDirectly(company.getId(), account.getId(), row.getStatementNo());

        VoucherProblemEditDoc doc = buildDoc("首推", DETAIL_ACCOUNT,
                new BigDecimal("345.67"), new BigDecimal("345.67"), MAPPED_BANK_ACCOUNT);
        problemService.saveEdit(record.getId(),
                new VoucherProblemService.VoucherProblemEditRequest(doc), operatorId);
        problemService.submit(record.getId(), operatorId);

        // 出列后 problem_type 已清空 → loadAccessible 404（不在问题列表中）
        VoucherProblemEditDoc again = buildDoc("再改", DETAIL_ACCOUNT,
                new BigDecimal("345.67"), new BigDecimal("345.67"), MAPPED_BANK_ACCOUNT);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> problemService.saveEdit(record.getId(),
                        new VoucherProblemService.VoucherProblemEditRequest(again), operatorId));
        assertEquals(404, ex.getCode(), "出列后再访问必须 404（不在问题列表）");
    }

    // ---- fixture ----

    private Company insertCompany(String aliasKeyword) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode("VPS_" + suffix);
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
        account.setAccountName("VPS problem-editor test account");
        account.setAccountNumber("6222" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("500000.00"));
        account.setStatus("ACTIVE");
        account.setAccountingMode(accountingMode);
        account.setKingdeeAccountNumber(kingdeeAccountNumber);
        bankAccountMapper.insert(account);
        return account;
    }

    private BankDataStatement insertBankRow(Long companyId, Long bankAccountId, String summary) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-VPS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setAdapterCode("CITIC");
        task.setBankAccountId(bankAccountId);
        task.setRequestId("qa-vps-" + UUID.randomUUID());
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
        raw.setPayload("{\"qa\":\"voucher-problem fixture\"}");
        raw.setRetentionUntil(LocalDateTime.now().plusDays(30));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(companyId);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(bankAccountId);
        statement.setStatementNo("VPS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
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
     * 直接造一条「已在问题桶」的标准流水（绕过一键推送，聚焦编辑器分支）：
     * APPROVED + NOT_PUSHED + problem_type=PROBLEM_MANUAL_AMOUNT。
     */
    private StatementRecord markProblemDirectly(Long companyId, Long bankAccountId, String statementNo) {
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("VPS-BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
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
        record.setRawPayload("{\"qa\":\"voucher-problem standard fixture\"}");
        record.setSummary("银行手续费");
        record.setValidationStatus("PASSED");
        record.setReviewStatus("APPROVED");
        record.setPushStatus("NOT_PUSHED");
        record.setProblemType("PROBLEM_MANUAL_AMOUNT");
        record.setProblemReason("测试预置落桶");
        record.setProblemUpdatedAt(LocalDateTime.now());
        statementRecordMapper.insert(record);
        return record;
    }

    /** 标准两行凭证：借明细科目 / 贷 1002（可选挂银行账号维度）。 */
    private VoucherProblemEditDoc buildDoc(String summary, String debitAccount,
                                           BigDecimal debitAmount, BigDecimal creditAmount,
                                           String bankDimensionValue) {
        return new VoucherProblemEditDoc(summary,
                List.of(new VoucherProblemEditDoc.ProblemLine(debitAccount, "测试科目",
                        null, null, debitAmount, "MANUAL", null)),
                List.of(new VoucherProblemEditDoc.ProblemLine("1002", "银行存款",
                        "BANK_ACCOUNT", bankDimensionValue, creditAmount, "MANUAL", null)),
                null, null);
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
}
