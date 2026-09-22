package com.finance.system.statement.vouchergroup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.voucherrule.KingdeeAccountCatalogService;
import com.finance.system.statement.voucherrule.KingdeeVoucherEngineService;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import com.finance.system.statement.voucherrule.dto.VoucherProblemEditDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A2 问题凭证编辑器（W16-A2，规划 docs/voucher-rule-long-task-plan-20260922.md 阶段 2）。
 *
 * <p>问题凭证闭环（落桶 → 修复 → 出列）：</p>
 * <ul>
 *   <li><b>列表</b>：{@code problem_type IS NOT NULL} 即在桶（落桶由 BankDataPushService
 *       一键推送时写入），按类型筛选 + 关键字搜索，problem_updated_at 倒序；</li>
 *   <li><b>详情</b>：流水上下文 + 落桶原因 + 规则预填分录（未保存过编辑态时）或
 *       已保存的编辑态；</li>
 *   <li><b>保存重校验</b>：借贷平衡 / 科目必明细 / 维度值必带类型，通过后写 problem_edit_json
 *       并留审计 PROBLEM_EDIT；</li>
 *   <li><b>提交推送</b>：以编辑态为准组装分录（从未保存过编辑态时用规则预填）走
 *       {@link KingdeeVoucherEngineService#pushManual}——成功 GL_PUSHED 自动出列，
 *       失败留桶并刷新 problem_reason。</li>
 * </ul>
 *
 * <p>权限与凭证中心一致（{@code voucher:push}）；跨公司口径同 VoucherGroupService。</p>
 */
@Service
public class VoucherProblemService {

    private static final Logger log = LoggerFactory.getLogger(VoucherProblemService.class);

    private static final String CROSS_COMPANY_PERMISSION = "bankdata:cross-company:view";
    private static final Set<String> KNOWN_PROBLEM_TYPES = Set.of(
            "PROBLEM_CANDIDATES", "PROBLEM_UNMATCHED", "PROBLEM_MANUAL_AMOUNT",
            "PROBLEM_ELIGIBLE", "PROBLEM_PUSH_FAILED");

    private final StatementRecordMapper statementMapper;
    private final StatementAuditEventMapper auditEventMapper;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final KingdeeAccountCatalogService accountCatalog;
    private final com.finance.system.statement.voucherrule.KingdeeVoucherEngineService voucherEngine;
    private final com.finance.system.statement.voucherrule.KingdeeVoucherMatchingService matchingService;
    private final com.finance.system.statement.voucherrule.KingdeeDimensionMappingService dimensionMappingService;
    private final com.finance.system.domain.mapper.BankAccountMapper bankAccountMapper;
    private final com.finance.system.domain.mapper.CompanyMapper companyMapper;
    private final ObjectMapper objectMapper;

    public VoucherProblemService(StatementRecordMapper statementMapper,
                                 StatementAuditEventMapper auditEventMapper,
                                 CompanyScopeService companyScope,
                                 RbacService rbacService,
                                 KingdeeAccountCatalogService accountCatalog,
                                 com.finance.system.statement.voucherrule.KingdeeVoucherEngineService voucherEngine,
                                 com.finance.system.statement.voucherrule.KingdeeVoucherMatchingService matchingService,
                                 com.finance.system.statement.voucherrule.KingdeeDimensionMappingService dimensionMappingService,
                                 com.finance.system.domain.mapper.BankAccountMapper bankAccountMapper,
                                 com.finance.system.domain.mapper.CompanyMapper companyMapper,
                                 ObjectMapper objectMapper) {
        this.statementMapper = statementMapper;
        this.auditEventMapper = auditEventMapper;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.accountCatalog = accountCatalog;
        this.voucherEngine = voucherEngine;
        this.matchingService = matchingService;
        this.dimensionMappingService = dimensionMappingService;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.objectMapper = objectMapper;
    }

    // ---------------- 列表 ----------------

    public PageResponse<ProblemRowResponse> pageProblems(int page, int size, String problemType,
                                                         String keyword, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        boolean crossCompany = rbacService.permissionCodesForUser(userId).contains(CROSS_COMPANY_PERMISSION);
        if (problemType != null && !problemType.isBlank() && !KNOWN_PROBLEM_TYPES.contains(problemType)) {
            throw new BusinessException(400, "未知的问题类型筛选：" + problemType);
        }
        LambdaQueryWrapper<StatementRecord> query = new LambdaQueryWrapper<StatementRecord>()
                .eq(!crossCompany, StatementRecord::getCompanyId, companyId)
                .isNotNull(StatementRecord::getProblemType)
                .eq(problemType != null && !problemType.isBlank(),
                        StatementRecord::getProblemType, problemType);
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            query.and(q -> q.like(StatementRecord::getStatementNo, like)
                    .or().like(StatementRecord::getSummary, like)
                    .or().like(StatementRecord::getCounterpartyName, like));
        }
        query.orderByDesc(StatementRecord::getProblemUpdatedAt)
                .orderByDesc(StatementRecord::getId);
        Page<StatementRecord> result = statementMapper.selectPage(new Page<>(page, size), query);
        List<ProblemRowResponse> rows = result.getRecords().stream().map(VoucherProblemService::toRow).toList();
        return new PageResponse<>(page, size, result.getTotal(), rows);
    }

    // ---------------- 详情 ----------------

    public ProblemDetailResponse getProblem(Long id, Long userId) {
        StatementRecord record = loadAccessible(id, userId);
        assertInProblemBucket(record);
        VoucherProblemEditDoc editDoc = parseEditDoc(record.getProblemEditJson());
        Prefill prefill = buildPrefill(record);
        return new ProblemDetailResponse(
                toRow(record),
                record.getDirection(),
                record.getAmount(),
                record.getCurrency(),
                record.getCounterpartyName(),
                record.getCounterpartyAccount(),
                record.getTransactionTime(),
                record.getValidationStatus(),
                record.getReviewStatus(),
                record.getPushStatus(),
                record.getVoucherNo(),
                editDoc,
                prefill.debitLines(),
                prefill.creditLines(),
                prefill.ruleNo(),
                prefill.businessType(),
                prefill.needManualAmount());
    }

    // ---------------- 保存重校验 ----------------

    @Transactional
    public ProblemRowResponse saveEdit(Long id, VoucherProblemEditRequest request, Long userId) {
        StatementRecord record = loadAccessible(id, userId);
        // 先桶后推送态：出列行直接 404（不在问题列表）；在桶但已推送成功 409。
        assertInProblemBucket(record);
        if ("PUSHED".equals(record.getPushStatus()) || "GL_PUSHED".equals(record.getPushStatus())) {
            throw new BusinessException(409, "该流水已推送金蝶，问题凭证编辑器不可再改");
        }
        if (request == null || request.editDoc() == null) {
            throw new BusinessException(400, "缺少编辑内容");
        }
        VoucherProblemEditDoc doc = request.editDoc()
                .normalizeAndValidate(accountCatalog::isDetailAccount)
                .withEditor(userId);

        int updated = statementMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getProblemEditJson, serialize(doc))
                .set(StatementRecord::getProblemUpdatedBy, userId)
                .set(StatementRecord::getProblemUpdatedAt, LocalDateTime.now())
                .eq(StatementRecord::getId, id)
                .isNotNull(StatementRecord::getProblemType));
        if (updated != 1) {
            throw new BusinessException(409, "流水状态已变化（可能已出列或被并发修改），请刷新后重试");
        }
        int lineCount = doc.debitLines().size() + doc.creditLines().size();
        insertAudit(record, "PROBLEM_EDIT", "SUCCEEDED", record.getReviewStatus(), record.getReviewStatus(),
                userId, "问题凭证编辑保存（借 " + doc.debitLines().size() + " 行 / 贷 "
                        + doc.creditLines().size() + " 行，共 " + lineCount + " 行）");
        return toRow(statementMapper.selectById(id));
    }

    // ---------------- 提交推送 ----------------

    /**
     * 修复完成提交推送：编辑态优先（没有则规则预填），走 pushManual。
     * 成功 → GL_PUSHED + 出列；失败 → 留桶并刷新 problem_reason（push_status 已由引擎写 GL_FAILED）。
     */
    @Transactional
    public SubmitResult submit(Long id, Long userId) {
        StatementRecord record = loadAccessible(id, userId);
        if ("PUSHED".equals(record.getPushStatus()) || "GL_PUSHED".equals(record.getPushStatus())) {
            throw new BusinessException(409, "该流水已推送金蝶（凭证号 " + record.getVoucherNo() + "），无需重复提交");
        }
        VoucherProblemEditDoc doc = parseEditDoc(record.getProblemEditJson());
        List<KingdeeVoucherEntryDraft> debits;
        List<KingdeeVoucherEntryDraft> credits;
        String explanation;
        if (doc != null) {
            // 槽位现场解析（不存快照）：槽位可在界面改，存快照会漂移。
            java.util.function.Function<String, String> slotResolver =
                    dimensionMappingService::slotOf;
            debits = doc.debitLines().stream().map(l -> l.toDraft("DEBIT", slotResolver)).toList();
            credits = doc.creditLines().stream().map(l -> l.toDraft("CREDIT", slotResolver)).toList();
            explanation = doc.summary();
        } else {
            Prefill prefill = buildPrefill(record);
            if (prefill.debitLines() == null || prefill.debitLines().isEmpty()) {
                throw new BusinessException(400, "该流水尚无可用分录：无规则命中（或预填为空），"
                        + "请先在编辑器中保存分录再提交");
            }
            // 预填含 MANUAL 行（金额 null）时不能直接推——payload 会带空金额；
            // 必须人工补金额保存编辑态后提交（与 PROBLEM_MANUAL_AMOUNT 落桶语义一致）。
            boolean hasUnfilledManual = prefill.debitLines().stream().anyMatch(KingdeeVoucherEntryDraft::manual)
                    || (prefill.creditLines() != null
                        && prefill.creditLines().stream().anyMatch(KingdeeVoucherEntryDraft::manual));
            if (hasUnfilledManual) {
                throw new BusinessException(400, "规则预填含人工分摊行（金额未填），"
                        + "请先在编辑器中补齐金额并保存，再提交推送");
            }
            debits = prefill.debitLines();
            credits = prefill.creditLines() == null ? List.of() : prefill.creditLines();
            explanation = null;
        }
        try {
            KingdeeVoucherEngineService.KingdeeVoucherPushResult result =
                    voucherEngine.pushManual(record.getId(), explanation, debits, credits, userId);
            insertAudit(record, "PROBLEM_SUBMIT", "SUCCEEDED", record.getReviewStatus(), record.getReviewStatus(),
                    userId, result.message());
            StatementRecord after = statementMapper.selectById(id);
            return new SubmitResult(result.status(), result.voucherNo(), result.message(),
                    after.getPushStatus(), toRow(after));
        } catch (BusinessException e) {
            // 失败留桶：刷新 problem_reason 供列表/详情直接看到最新失败原因（引擎已写 GL_FAILED + 审计）。
            statementMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                    .set(StatementRecord::getProblemReason, truncate(e.getMessage(), 500))
                    .set(StatementRecord::getProblemUpdatedBy, userId)
                    .set(StatementRecord::getProblemUpdatedAt, LocalDateTime.now())
                    .eq(StatementRecord::getId, id));
            insertAudit(record, "PROBLEM_SUBMIT", "FAILED", record.getReviewStatus(), record.getReviewStatus(),
                    userId, truncate(e.getMessage(), 1000));
            throw e;
        }
    }

    // ---------------- 内部 ----------------

    private record Prefill(List<KingdeeVoucherEntryDraft> debitLines,
                           List<KingdeeVoucherEntryDraft> creditLines,
                           Integer ruleNo, String businessType, boolean needManualAmount) {
    }

    /**
     * 规则预填（详情回显 / submit 无编辑态兜底）：按当前规则表对单条流水跑 preview。
     * AUTO_FILL 取唯一候选；CANDIDATES 取第一个（businessType 供前端展示「候选 N 条」）；
     * UNMATCHED/NOT_ELIGIBLE 返回空（前端展示落桶原因，必须人工编辑保存后再提交）。
     */
    private Prefill buildPrefill(StatementRecord record) {
        if (!"APPROVED".equals(record.getReviewStatus())) {
            return new Prefill(null, null, null, null, false);
        }
        try {
            com.finance.system.domain.entity.BankAccount account = record.getBankAccountId() == null
                    ? null : bankAccountMapper.selectById(record.getBankAccountId());
            com.finance.system.domain.entity.Company company = record.getCompanyId() == null
                    ? null : companyMapper.selectById(record.getCompanyId());
            KingdeeVoucherRulePreview preview =
                    voucherEngine.previewOne(record, account, company);
            if (preview.candidates() == null || preview.candidates().isEmpty()) {
                return new Prefill(null, null, null, preview.reason(), false);
            }
            KingdeeVoucherRulePreview.Candidate candidate = preview.candidates().get(0);
            return new Prefill(candidate.debitLines(), candidate.creditLines(),
                    candidate.ruleNo(), candidate.businessType(), candidate.needManualAmount());
        } catch (Exception e) {
            log.warn("问题凭证规则预填失败（编辑器以人工编辑态为准）statementId={}：{}", record.getId(), e.getMessage());
            return new Prefill(null, null, null, null, false);
        }
    }

    private StatementRecord loadAccessible(Long id, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        boolean crossCompany = rbacService.permissionCodesForUser(userId).contains(CROSS_COMPANY_PERMISSION);
        StatementRecord record = statementMapper.selectById(id);
        if (record == null || record.getCompanyId() == null
                || (record.getCompanyId() != companyId && !crossCompany)) {
            throw new BusinessException(404, "流水不存在或不在当前公司域内");
        }
        return record;
    }

    /** 公司域校验后的问题在桶校验（出列 / 从未落桶的行不可进编辑器）。 */
    private void assertInProblemBucket(StatementRecord record) {
        if (record.getProblemType() == null) {
            throw new BusinessException(404, "该流水不在问题凭证列表中（可能已出列）");
        }
    }

    private VoucherProblemEditDoc parseEditDoc(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VoucherProblemEditDoc.class);
        } catch (Exception e) {
            log.warn("问题凭证编辑态解析失败（按无编辑态处理）statementEditJson 长度={}：{}", json.length(), e.getMessage());
            return null;
        }
    }

    private String serialize(VoucherProblemEditDoc doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new BusinessException(500, "编辑态序列化失败：" + e.getMessage());
        }
    }

    private static ProblemRowResponse toRow(StatementRecord row) {
        return new ProblemRowResponse(
                row.getId(),
                row.getStatementNo(),
                row.getProblemType(),
                row.getProblemReason(),
                row.getProblemUpdatedAt(),
                row.getVoucherNo(),
                row.getTransactionTime(),
                row.getDirection(),
                row.getAmount(),
                row.getCurrency(),
                row.getSummary(),
                row.getCounterpartyName(),
                row.getReviewStatus(),
                row.getPushStatus());
    }

    private void insertAudit(StatementRecord record, String action, String result,
                             String previous, String current, Long operatorId, String detail) {
        StatementAuditEvent event = new StatementAuditEvent();
        event.setCompanyId(record.getCompanyId());
        event.setStatementId(record.getId());
        event.setBatchId(record.getBatchId());
        event.setAction(action);
        event.setResult(result);
        event.setPreviousStatus(previous);
        event.setCurrentStatus(current);
        event.setOperatorId(operatorId);
        event.setDetail(detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail));
        auditEventMapper.insert(event);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    // ---------------- DTO ----------------

    /** 列表行（凭证中心问题桶 / 编辑器列表共用）。 */
    public record ProblemRowResponse(
            Long id,
            String statementNo,
            String problemType,
            String problemReason,
            LocalDateTime problemUpdatedAt,
            String voucherNo,
            LocalDateTime transactionTime,
            String direction,
            java.math.BigDecimal amount,
            String currency,
            String summary,
            String counterpartyName,
            String reviewStatus,
            String pushStatus) {
    }

    /** 详情：流水上下文 + 落桶原因 + 编辑态 + 规则预填。 */
    public record ProblemDetailResponse(
            ProblemRowResponse row,
            String direction,
            java.math.BigDecimal amount,
            String currency,
            String counterpartyName,
            String counterpartyAccount,
            LocalDateTime transactionTime,
            String validationStatus,
            String reviewStatus,
            String pushStatus,
            String voucherNo,
            VoucherProblemEditDoc editDoc,
            List<KingdeeVoucherEntryDraft> prefillDebitLines,
            List<KingdeeVoucherEntryDraft> prefillCreditLines,
            Integer prefillRuleNo,
            String prefillBusinessType,
            boolean prefillNeedManualAmount) {
    }

    /** PUT 请求体。 */
    public record VoucherProblemEditRequest(VoucherProblemEditDoc editDoc) {
    }

    /** submit 结果。 */
    public record SubmitResult(String status, String voucherNo, String message,
                               String pushStatus, ProblemRowResponse row) {
    }
}
