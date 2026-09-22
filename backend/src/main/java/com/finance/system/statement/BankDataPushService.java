package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.dto.PushBatchResult;
import com.finance.system.statement.dto.PushRowResult;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementTransferRequest;
import com.finance.system.statement.voucherrule.KingdeeVoucherEngineService;
import com.finance.system.statement.voucherrule.KingdeeVoucherMatchingService;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一键推送至金蝶——规则编排服务（W16-A1，2026-09-22 用户拍板）。
 *
 * <p>AI 制证全链路退役后的唯一制证编排：勾选银行流水 → 转入标准流水（幂等）→
 * <b>始终按当前规则表</b>跑 {@link KingdeeVoucherMatchingService}：</p>
 * <ul>
 *   <li><b>唯一命中（AUTO_FILL）且无需人工金额</b> → 复核内化（自动 APPROVED 并留审计）
 *   → {@link KingdeeVoucherEngineService#push} 自动推送金蝶草稿；</li>
 *   <li><b>多候选（CANDIDATES）/ 未命中（UNMATCHED）/ 需人工金额 / 不可制证（NOT_ELIGIBLE）/
 *   推送失败</b> → 全部落为「问题凭证」（{@code PROBLEM_*}），凭证中心可查，
 *   A2 编辑器上线前在凭证草稿工作台人工处理；</li>
 *   <li><b>跳过</b>：纯人工制证账户（MANUAL）、无公司归属、越权（无 cross-company 权限）、
 *   已人工驳回（尊重驳回结果）；</li>
 *   <li><b>幂等跳过</b>：此前已推送成功（PUSHED/GL_PUSHED）是终局，重跑不动它。</li>
 * </ul>
 *
 * <p>口径决策（与用户拍板一一对应）：</p>
 * <ul>
 *   <li><b>重跑口径：始终按当前规则表</b>——不读 ai_suggestion_json，历史 AI 草稿与
 *   推送失败的行勾选即按现行规则重新匹配；</li>
 *   <li><b>撤回态复活</b>沿用 V39 语义（唯一键决定只能复用同一条记录）；</li>
 *   <li><b>写后断言影响行数</b>——历次「假成功」均出自 update 命中 0 行却报成功（§15.3）。</li>
 * </ul>
 */
@Service
public class BankDataPushService {

    private static final String MANUAL_MODE = "MANUAL";
    private static final String VALIDATION_PASSED = "PASSED";
    private static final String REVIEW_PENDING = "PENDING";
    private static final String REVIEW_APPROVED = "APPROVED";
    private static final String REVIEW_REJECTED = "REJECTED";
    private static final String REVIEW_WITHDRAWN = "WITHDRAWN";
    private static final String PUSH_NOT_PUSHED = "NOT_PUSHED";

    private final BankDataStatementMapper bankDataStatementMapper;
    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final StatementRecordMapper recordMapper;
    private final StatementAuditEventMapper auditEventMapper;
    private final StatementService statementService;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final com.finance.system.closing.ClosingService closingService;
    private final KingdeeVoucherEngineService voucherEngine;

    public BankDataPushService(BankDataStatementMapper bankDataStatementMapper,
                               BankAccountMapper bankAccountMapper,
                               CompanyMapper companyMapper,
                               StatementRecordMapper recordMapper,
                               StatementAuditEventMapper auditEventMapper,
                               StatementService statementService,
                               CompanyScopeService companyScope,
                               RbacService rbacService,
                               com.finance.system.closing.ClosingService closingService,
                               KingdeeVoucherEngineService voucherEngine) {
        this.bankDataStatementMapper = bankDataStatementMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.recordMapper = recordMapper;
        this.auditEventMapper = auditEventMapper;
        this.statementService = statementService;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.closingService = closingService;
        this.voucherEngine = voucherEngine;
    }

    /**
     * 一键推送主入口（后台任务逐行调用的同步体）。
     *
     * <p>前置预检（逐行回报，不整批失败）→ 按公司分批转入 → 逐行规则匹配与推送。
     * 单行失败（含金蝶 502）不影响其他行；整体异常由任务层兜底标 FAILED。</p>
     */
    public PushBatchResult pushToKingdee(List<Long> statementIds, Long operatorId) {
        List<Long> ids = statementIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请选择要推送的银行流水");
        }
        List<BankDataStatement> rows = bankDataStatementMapper.selectBatchIds(ids);
        if (rows.size() != ids.size()) {
            throw new BusinessException(404, "部分银行流水不存在或已被清理，请刷新后重试");
        }

        long ownCompanyId = companyScope.companyIdForUser(operatorId);
        boolean crossCompany = rbacService.permissionCodesForUser(operatorId)
                .contains("bankdata:cross-company:view");

        // 按行预检：账户制证模式 + 公司归属（跨公司行要求 cross-company 权限）。
        List<BankDataStatement> eligible = new ArrayList<>();
        Map<Long, PushRowResult> results = new LinkedHashMap<>();
        for (BankDataStatement row : rows) {
            BankAccount account = row.getBankAccountId() == null ? null
                    : bankAccountMapper.selectById(row.getBankAccountId());
            if (account != null && MANUAL_MODE.equalsIgnoreCase(account.getAccountingMode())) {
                results.put(row.getId(), new PushRowResult(row.getId(), statementNoOf(row),
                        "SKIPPED_MANUAL", null, null, null,
                        "账户为纯人工制证模式：数据保留在系统，请在金蝶手工制证"));
                continue;
            }
            if (row.getCompanyId() == null) {
                results.put(row.getId(), new PushRowResult(row.getId(), statementNoOf(row),
                        "SKIPPED", null, null, null, "银行流水缺少公司归属，无法制证"));
                continue;
            }
            if (row.getCompanyId() != ownCompanyId && !crossCompany) {
                results.put(row.getId(), new PushRowResult(row.getId(), statementNoOf(row),
                        "SKIPPED", null, null, null, "没有对其他公司主体流水制证的权限"));
                continue;
            }
            eligible.add(row);
        }

        // W7 账期锁：CLOSED 账期禁止制证（导入/草稿/推送全链），按行归属公司+交易时间归月预检。
        rows.stream().map(BankDataStatement::getCompanyId).filter(Objects::nonNull).distinct()
                .forEach(companyId -> closingService.ensurePeriodsOpen(companyId,
                        rows.stream().filter(r -> companyId.equals(r.getCompanyId()))
                                .map(BankDataStatement::getTransactionTime).toList()));

        // 复用既有转入链路（按公司分批：transferFromBankData 要求单公司批次）。
        String[] batchNoHolder = new String[1];
        rows.stream().map(BankDataStatement::getCompanyId).filter(Objects::nonNull).distinct().forEach(companyId -> {
            List<Long> groupIds = eligible.stream()
                    .filter(row -> row.getCompanyId().equals(companyId))
                    .map(BankDataStatement::getId).toList();
            if (groupIds.isEmpty()) {
                return;
            }
            StatementImportBatchResponse batch = statementService
                    .transferFromBankData(new StatementTransferRequest(groupIds), operatorId);
            if (batchNoHolder[0] == null) {
                batchNoHolder[0] = batch.batchNo();
            }
        });

        // 逐行：规则匹配 → 唯一命中自动推 / 其余进问题凭证。
        for (BankDataStatement row : eligible) {
            results.put(row.getId(), processRow(row, operatorId));
        }

        List<PushRowResult> ordered = ids.stream().map(results::get)
                .filter(Objects::nonNull).toList();
        return new PushBatchResult(
                batchNoHolder[0],
                ordered.size(),
                (int) ordered.stream().filter(r -> "PUSHED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> PushRowResult.isProblem(r.outcome())).count(),
                (int) ordered.stream().filter(r -> r.outcome() != null && r.outcome().startsWith("SKIPPED")).count(),
                (int) ordered.stream().filter(r -> "ALREADY_PUSHED".equals(r.outcome())).count(),
                ordered);
    }

    /** 单行编排：读标准流水 → 规则匹配 → 分流（自动推 / 问题凭证 / 跳过）。 */
    private PushRowResult processRow(BankDataStatement row, Long operatorId) {
        String statementNo = statementNoOf(row);
        StatementRecord record = recordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, row.getCompanyId())
                .eq(StatementRecord::getStatementNo, statementNo.trim())
                .last("LIMIT 1"));
        if (record == null) {
            return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, null,
                    "转入后未找到对应标准流水（校验未通过）");
        }
        if (!VALIDATION_PASSED.equals(record.getValidationStatus())) {
            return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, record.getPushStatus(),
                    "流水校验未通过：" + trimToEmpty(record.getValidationMessage()));
        }
        if ("PUSHED".equals(record.getPushStatus()) || "GL_PUSHED".equals(record.getPushStatus())) {
            return new PushRowResult(row.getId(), statementNo, "ALREADY_PUSHED", null,
                    record.getVoucherNo(), record.getPushStatus(), "此前已推送金蝶（终局不动，幂等跳过）");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            return new PushRowResult(row.getId(), statementNo, "SKIPPED", null,
                    record.getVoucherNo(), record.getPushStatus(),
                    "该流水此前已被人工驳回：" + trimToEmpty(record.getReviewComment()));
        }
        // 撤回态先复活为待复核（唯一键决定只能复用同一条记录），再走规则匹配。
        if (REVIEW_WITHDRAWN.equals(record.getReviewStatus()) && !reviveWithdrawn(record, operatorId)) {
            return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, record.getPushStatus(),
                    "该流水已被撤回，复位失败（可能状态已变化），请刷新后重试");
        }
        // 兜底：只有待复核/已复核两种状态能继续，其余明确落问题凭证（不静默往下走）。
        if (!REVIEW_PENDING.equals(record.getReviewStatus())
                && !REVIEW_APPROVED.equals(record.getReviewStatus())) {
            return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, record.getPushStatus(),
                    "流水当前状态为 " + trimToEmpty(record.getReviewStatus())
                            + "，请先在「凭证中心」重新打开");
        }

        // 复核闸门内化：待复核 → 自动通过并留审计（与既有链路同口径——复核职责转移到金蝶侧）。
        if (REVIEW_PENDING.equals(record.getReviewStatus())) {
            String comment = "一键推送至金蝶：规则引擎自动制证（复核由金蝶侧人工完成）";
            int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                    .set(StatementRecord::getReviewStatus, REVIEW_APPROVED)
                    .set(StatementRecord::getReviewComment, comment)
                    .set(StatementRecord::getReviewedBy, operatorId)
                    .set(StatementRecord::getReviewedAt, java.time.LocalDateTime.now())
                    .eq(StatementRecord::getId, record.getId())
                    .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
            // 写后断言影响行数（§15.3 纪律）：命中 0 行说明状态已被并发改动，落问题凭证重查。
            if (updated != 1) {
                return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, record.getPushStatus(),
                        "流水复核状态已变化，请刷新后重试");
            }
            record.setReviewStatus(REVIEW_APPROVED);
            insertAudit(record, "AUTO_PUSH_REVIEW", "SUCCESS", REVIEW_PENDING, REVIEW_APPROVED,
                    operatorId, comment);
        }

        // 规则匹配（始终按当前规则表；不改 ai_suggestion_json）。
        BankAccount account = record.getBankAccountId() == null ? null
                : bankAccountMapper.selectById(record.getBankAccountId());
        Company company = record.getCompanyId() == null ? null
                : companyMapper.selectById(record.getCompanyId());
        KingdeeVoucherRulePreview preview = voucherEngine.previewOne(record, account, company);
        String status = preview.status();

        if (KingdeeVoucherMatchingService.ST_AUTO_FILL.equals(status)) {
            return autoPush(row.getId(), statementNo, record, preview, operatorId);
        }
        if (KingdeeVoucherMatchingService.ST_CANDIDATES.equals(status)) {
            return problem(row.getId(), statementNo, "PROBLEM_CANDIDATES", null, record.getPushStatus(),
                    "多条规则同时命中（" + preview.candidates().size() + " 条），需人工在凭证草稿工作台确认");
        }
        if (KingdeeVoucherMatchingService.ST_UNMATCHED.equals(status)) {
            return problem(row.getId(), statementNo, "PROBLEM_UNMATCHED", null, record.getPushStatus(),
                    "无命中规则：请检查凭证规则中心，或在凭证草稿工作台人工制证");
        }
        // NOT_ELIGIBLE（预览阶段理论上已被上方状态检查拦截，兜底记录原因）。
        return problem(row.getId(), statementNo, "PROBLEM_ELIGIBLE", null, record.getPushStatus(),
                trimToEmpty(preview.reason()));
    }

    /**
     * 唯一命中自动推：先看是否需要人工金额（需要则落问题凭证，不能带空金额调金蝶），
     * 再走 {@link KingdeeVoucherEngineService#push}（P1-5 锁 / 账期锁 / 幂等 / 第二张凭证全继承）。
     */
    private PushRowResult autoPush(Long bankDataStatementId, String statementNo,
                                   StatementRecord record, KingdeeVoucherRulePreview preview,
                                   Long operatorId) {
        KingdeeVoucherRulePreview.Candidate candidate = preview.candidates().get(0);
        if (candidate.ruleNo() == null) {
            return problem(bankDataStatementId, statementNo, "PROBLEM_UNMATCHED", null,
                    record.getPushStatus(), "命中规则缺少规则号（数据异常），请检查凭证规则中心");
        }
        if (candidate.needManualAmount()) {
            return problem(bankDataStatementId, statementNo, "PROBLEM_MANUAL_AMOUNT",
                    candidate.ruleNo(), record.getPushStatus(),
                    "唯一命中规则「" + candidate.businessType() + "」含人工分摊行，需在凭证草稿工作台补金额");
        }
        try {
            KingdeeVoucherEngineService.KingdeeVoucherPushResult pushed =
                    voucherEngine.push(record.getId(), candidate.ruleNo(), null, operatorId);
            return new PushRowResult(bankDataStatementId, statementNo, "PUSHED", candidate.ruleNo(),
                    pushed.voucherNo(), "GL_PUSHED", pushed.message());
        } catch (BusinessException e) {
            // 推送失败：状态与原因已由引擎落库（GL_FAILED + 审计），这里只负责行级回报。
            return problem(bankDataStatementId, statementNo, "PROBLEM_PUSH_FAILED",
                    candidate.ruleNo(), "GL_FAILED", e.getMessage());
        }
    }

    /** 问题凭证行结果（凭证中心可查；A2 编辑器上线前在凭证草稿工作台人工处理）。 */
    private static PushRowResult problem(Long bankDataStatementId, String statementNo, String outcome,
                                         Integer ruleNo, String pushStatus, String message) {
        return new PushRowResult(bankDataStatementId, statementNo, outcome, ruleNo, null,
                pushStatus, trimToEmpty(message));
    }

    /**
     * 撤回态复活（V39 语义）：把 WITHDRAWN 复位为待复核并清掉撤回/凭证号痕迹。
     * 返回是否真的更新了 1 行（并发下可能已被别处改动）。
     */
    private boolean reviveWithdrawn(StatementRecord record, Long operatorId) {
        int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, REVIEW_PENDING)
                .set(StatementRecord::getPushStatus, PUSH_NOT_PUSHED)
                .set(StatementRecord::getPushMessage, null)
                .set(StatementRecord::getVoucherNo, null)
                .set(StatementRecord::getWithdrawnAt, null)
                .set(StatementRecord::getWithdrawnBy, null)
                .eq(StatementRecord::getId, record.getId())
                .eq(StatementRecord::getReviewStatus, REVIEW_WITHDRAWN));
        if (updated == 1) {
            record.setReviewStatus(REVIEW_PENDING);
            record.setPushStatus(PUSH_NOT_PUSHED);
            insertAudit(record, "STATEMENT_REVIVE", "SUCCESS", REVIEW_WITHDRAWN, REVIEW_PENDING,
                    operatorId, "撤回后重新推送：记录复位为待复核");
            return true;
        }
        return false;
    }

    private void insertAudit(StatementRecord record, String action, String result, String previous,
                             String current, Long operatorId, String detail) {
        StatementAuditEvent event = new StatementAuditEvent();
        event.setCompanyId(record.getCompanyId());
        event.setStatementId(record.getId());
        event.setBatchId(record.getBatchId());
        event.setAction(action);
        event.setResult(result);
        event.setPreviousStatus(previous);
        event.setCurrentStatus(current);
        event.setOperatorId(operatorId);
        event.setDetail(detail);
        auditEventMapper.insert(event);
    }

    private static String statementNoOf(BankDataStatement row) {
        return row.getStatementNo() == null || row.getStatementNo().isBlank()
                ? "BKD-" + row.getId() : row.getStatementNo().trim();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value;
    }
}
