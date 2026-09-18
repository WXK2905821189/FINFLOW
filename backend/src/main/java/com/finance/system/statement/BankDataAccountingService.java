package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.AccountingSuggestionService;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
import com.finance.system.ai.dto.VoucherEntry;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.dto.AiVoucherBatchResponse;
import com.finance.system.statement.dto.AiVoucherRowResult;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementResponse;
import com.finance.system.statement.dto.StatementTransferRequest;
import com.finance.system.statement.dto.VoucherDraftSaveRequest;
import com.finance.system.statement.dto.VoucherSuggestionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 一键 AI 制证（2026-09-16 决策，2026-09-17 扩展草稿模式）：银行流水行 → 转入标准流水（幂等）→
 * AI 入账建议 → 按 mode 分流：
 * <ul>
 *   <li><b>PUSH</b>：复核闸门内化（自动 APPROVED）→ 推送金蝶（save→submit，audit 留给金蝶侧人工审核）；</li>
 *   <li><b>DRAFT</b>：AI 建议落为复核意见（reviewComment），流水停在 PENDING 草稿，
 *   人工在「凭证草稿与制证」页批量/逐行审核（batch-review）后推送（batch-push）——AI 只预填不执行。</li>
 * </ul>
 *
 * <p>流程决策记录：
 * <ul>
 *   <li>复用 {@link StatementService#transferFromBankData} 完成转入（含跨公司权限、批次、
 *   查重与 IMPORT 审计），本服务不重复实现转入逻辑；</li>
 *   <li>PUSH 模式复核不再要求「导入者不能复核自己」——复核职责转移到金蝶侧人工审核，
 *   系统内自动置 APPROVED 并留审计事件（action=AI_VOUCHER_REVIEW）；DRAFT 模式保持 PENDING，
 *   人工审核走 batch-review（BANKDATA 批次允许生成人自审，审计 action=REVIEW_APPROVE/REJECT）；</li>
 *   <li>AI 建议失败（未配置/无 ai:use 权限/限频/解析失败）时降级：PUSH 模式继续推送，DRAFT 模式
 *   仍生成草稿并在复核意见中标注「AI 建议不可用」，逐行结果以 aiStatus 标注；</li>
 *   <li>MANUAL 制证模式账户（V31）的行跳过推送，数据仍正常落库与查询（纯人工制证）。</li>
 * </ul></p>
 */
@Service
public class BankDataAccountingService {

    private static final String MANUAL_MODE = "MANUAL";
    private static final String VALIDATION_PASSED = "PASSED";
    private static final String REVIEW_PENDING = "PENDING";
    private static final String REVIEW_APPROVED = "APPROVED";
    private static final String REVIEW_REJECTED = "REJECTED";

    private final BankDataStatementMapper bankDataStatementMapper;
    private final BankAccountMapper bankAccountMapper;
    private final StatementRecordMapper recordMapper;
    private final StatementAuditEventMapper auditEventMapper;
    private final StatementService statementService;
    private final AccountingSuggestionService aiSuggestionService;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final ObjectMapper objectMapper;

    public BankDataAccountingService(BankDataStatementMapper bankDataStatementMapper,
                                     BankAccountMapper bankAccountMapper,
                                     StatementRecordMapper recordMapper,
                                     StatementAuditEventMapper auditEventMapper,
                                     StatementService statementService,
                                     AccountingSuggestionService aiSuggestionService,
                                     CompanyScopeService companyScope,
                                     RbacService rbacService,
                                     ObjectMapper objectMapper) {
        this.bankDataStatementMapper = bankDataStatementMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.recordMapper = recordMapper;
        this.auditEventMapper = auditEventMapper;
        this.statementService = statementService;
        this.aiSuggestionService = aiSuggestionService;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiVoucherBatchResponse createVouchers(List<Long> statementIds, Long operatorId) {
        return createVouchers(statementIds, operatorId, "PUSH");
    }

    @Transactional
    public AiVoucherBatchResponse createVouchers(List<Long> statementIds, Long operatorId, String requestMode) {
        String mode = requestMode == null || requestMode.isBlank() ? "PUSH" : requestMode.trim().toUpperCase(Locale.ROOT);
        if (!"PUSH".equals(mode) && !"DRAFT".equals(mode)) {
            throw new BusinessException(400, "制证模式仅支持 DRAFT（生成草稿）或 PUSH（直接推送）");
        }
        List<Long> ids = statementIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请选择要制证的银行流水");
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
        Map<Long, AiVoucherRowResult> results = new LinkedHashMap<>();
        for (BankDataStatement row : rows) {
            BankAccount account = row.getBankAccountId() == null ? null
                    : bankAccountMapper.selectById(row.getBankAccountId());
            if (account != null && MANUAL_MODE.equalsIgnoreCase(account.getAccountingMode())) {
                results.put(row.getId(), new AiVoucherRowResult(row.getId(),
                        row.getStatementNo(), "SKIPPED_MANUAL", null, null, null, null, null,
                        null, null, "账户为纯人工制证模式：数据保留在系统，请在金蝶手工制证"));
                continue;
            }
            if (row.getCompanyId() == null) {
                results.put(row.getId(), new AiVoucherRowResult(row.getId(),
                        row.getStatementNo(), "FAILED", null, null, null, null, null,
                        null, null, "银行流水缺少公司归属，无法制证"));
                continue;
            }
            if (row.getCompanyId() != ownCompanyId && !crossCompany) {
                results.put(row.getId(), new AiVoucherRowResult(row.getId(),
                        row.getStatementNo(), "FAILED", null, null, null, null, null,
                        null, null, "没有对其他公司主体流水制证的权限"));
                continue;
            }
            eligible.add(row);
        }

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

        // 逐行：AI 建议 → 按模式分流（DRAFT 停在草稿 / PUSH 复核内化后推送）。
        for (BankDataStatement row : eligible) {
            results.put(row.getId(), "DRAFT".equals(mode)
                    ? processRowAsDraft(row, operatorId)
                    : processRow(row, operatorId));
        }

        List<AiVoucherRowResult> ordered = ids.stream().map(results::get)
                .filter(Objects::nonNull).toList();
        return new AiVoucherBatchResponse(
                batchNoHolder[0],
                ordered.size(),
                (int) ordered.stream().filter(r -> "DRAFT_CREATED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> "PUSHED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> "ALREADY_PUSHED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> r.outcome() != null && r.outcome().startsWith("SKIPPED")
                        || "ALREADY_APPROVED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> "FAILED".equals(r.outcome())
                        || "FAILED_VALIDATION".equals(r.outcome())).count(),
                ordered);
    }

    private AiVoucherRowResult processRow(BankDataStatement row, Long operatorId) {
        String statementNo = firstNonBlank(row.getStatementNo(), "BKD-" + row.getId());
        StatementRecord record = recordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, row.getCompanyId())
                .eq(StatementRecord::getStatementNo, statementNo.trim())
                .last("LIMIT 1"));
        if (record == null) {
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED_VALIDATION", null,
                    null, null, null, null, null, null, "转入后未找到对应标准流水（校验未通过）");
        }
        if (!VALIDATION_PASSED.equals(record.getValidationStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED_VALIDATION", null,
                    null, null, null, null, null, record.getPushStatus(), record.getValidationMessage());
        }
        if ("PUSHED".equals(record.getPushStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "ALREADY_PUSHED", null,
                    null, null, null, null, record.getVoucherNo(), record.getPushStatus(),
                    "此前已推送金蝶（幂等跳过）");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "SKIPPED_REJECTED", null,
                    null, null, null, null, record.getVoucherNo(), record.getPushStatus(),
                    "该流水此前已被人工驳回：" + trimToEmpty(record.getReviewComment()));
        }

        // AI 建议：失败降级（金蝶侧人工审核兜底），不阻断推送。
        AiAccountingSuggestionResponse suggestion = null;
        String aiStatus = "OK";
        String aiNote = null;
        try {
            suggestion = aiSuggestionService.suggest(record.getId(), operatorId);
        } catch (BusinessException e) {
            aiStatus = "UNAVAILABLE";
            aiNote = e.getMessage();
        }

        // 复核闸门内化：自动通过并留审计（review() 的「导入者不能复核自己」规则不适用于
        // 一键链路——复核职责已转移到金蝶侧人工审核）。
        if (REVIEW_PENDING.equals(record.getReviewStatus())) {
            String comment = suggestion != null && suggestion.suggestedSummary() != null
                    ? "AI 辅助制证：" + suggestion.suggestedSummary() + "（复核由金蝶侧人工完成）"
                    : "一键 AI 制证（AI 建议不可用，复核由金蝶侧人工完成）";
            int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                    .set(StatementRecord::getReviewStatus, REVIEW_APPROVED)
                    .set(StatementRecord::getReviewComment, comment)
                    .set(StatementRecord::getAiSuggestionJson,
                            suggestion == null ? null : serializeSuggestion(suggestion))
                    .set(StatementRecord::getReviewedBy, operatorId)
                    .set(StatementRecord::getReviewedAt, LocalDateTime.now())
                    .eq(StatementRecord::getId, record.getId())
                    .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
            if (updated == 1) {
                insertAudit(record, "AI_VOUCHER_REVIEW", "SUCCESS", REVIEW_PENDING, REVIEW_APPROVED,
                        operatorId, comment);
            }
        }

        try {
            StatementResponse pushed = statementService.pushVoucher(record.getId(), operatorId);
            if ("PUSHED".equals(pushed.pushStatus())) {
                return new AiVoucherRowResult(row.getId(), statementNo, "PUSHED", aiStatus,
                        suggestion == null ? null : suggestion.businessCategory(),
                        suggestion == null ? null : suggestion.suggestedSummary(),
                        suggestion == null ? null : suggestion.suggestedSubject(),
                        suggestion == null ? null : suggestion.confidence(),
                        pushed.voucherNo(), pushed.pushStatus(),
                        aiNote == null ? trimToEmpty(pushed.pushMessage())
                                : "AI 建议不可用（" + aiNote + "）；" + trimToEmpty(pushed.pushMessage()));
            }
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED", aiStatus,
                    suggestion == null ? null : suggestion.businessCategory(),
                    suggestion == null ? null : suggestion.suggestedSummary(),
                    suggestion == null ? null : suggestion.suggestedSubject(),
                    suggestion == null ? null : suggestion.confidence(),
                    pushed.voucherNo(), pushed.pushStatus(),
                    trimToEmpty(pushed.pushMessage()));
        } catch (BusinessException e) {
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED", aiStatus,
                    suggestion == null ? null : suggestion.businessCategory(),
                    suggestion == null ? null : suggestion.suggestedSummary(),
                    suggestion == null ? null : suggestion.suggestedSubject(),
                    suggestion == null ? null : suggestion.confidence(),
                    null, record.getPushStatus(), e.getMessage());
        }
    }

    /**
     * DRAFT 模式逐行处理：转入后的标准流水停留 PENDING，AI 建议写进复核意见（reviewComment），
     * 审计 action=AI_VOUCHER_DRAFT；推送留给人工在「凭证草稿与制证」页 batch-review 后 batch-push。
     */
    private AiVoucherRowResult processRowAsDraft(BankDataStatement row, Long operatorId) {
        String statementNo = firstNonBlank(row.getStatementNo(), "BKD-" + row.getId());
        StatementRecord record = recordMapper.selectOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, row.getCompanyId())
                .eq(StatementRecord::getStatementNo, statementNo.trim())
                .last("LIMIT 1"));
        if (record == null) {
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED_VALIDATION", null,
                    null, null, null, null, null, null, "转入后未找到对应标准流水（校验未通过）");
        }
        if (!VALIDATION_PASSED.equals(record.getValidationStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "FAILED_VALIDATION", null,
                    null, null, null, null, null, record.getPushStatus(), record.getValidationMessage());
        }
        if ("PUSHED".equals(record.getPushStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "ALREADY_PUSHED", null,
                    null, null, null, null, record.getVoucherNo(), record.getPushStatus(),
                    "此前已推送金蝶（幂等跳过）");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "SKIPPED_REJECTED", null,
                    null, null, null, null, record.getVoucherNo(), record.getPushStatus(),
                    "该流水此前已被人工驳回：" + trimToEmpty(record.getReviewComment()));
        }
        if (REVIEW_APPROVED.equals(record.getReviewStatus())) {
            return new AiVoucherRowResult(row.getId(), statementNo, "ALREADY_APPROVED", null,
                    null, null, null, null, record.getVoucherNo(), record.getPushStatus(),
                    "此前已通过复核，可直接在「凭证草稿与制证」页推送");
        }

        // AI 建议：失败降级仍生成草稿（复核意见标注不可用，可在草稿页重新生成）。
        AiAccountingSuggestionResponse suggestion = null;
        String aiStatus = "OK";
        String aiNote = null;
        try {
            suggestion = aiSuggestionService.suggest(record.getId(), operatorId);
        } catch (BusinessException e) {
            aiStatus = "UNAVAILABLE";
            aiNote = e.getMessage();
        }
        String comment = suggestion != null ? formatSuggestion(suggestion)
                : "AI 建议不可用：" + trimToEmpty(aiNote) + "（可在「凭证草稿与制证」页重新生成）";
        int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewComment, comment)
                .set(StatementRecord::getAiSuggestionJson,
                        suggestion == null ? null : serializeSuggestion(suggestion))
                .eq(StatementRecord::getId, record.getId())
                .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
        if (updated == 1) {
            insertAudit(record, "AI_VOUCHER_DRAFT", "SUCCESS", REVIEW_PENDING, REVIEW_PENDING,
                    operatorId, comment);
        }
        return new AiVoucherRowResult(row.getId(), statementNo, "DRAFT_CREATED", aiStatus,
                suggestion == null ? null : suggestion.businessCategory(),
                suggestion == null ? null : suggestion.suggestedSummary(),
                suggestion == null ? null : suggestion.suggestedSubject(),
                suggestion == null ? null : suggestion.confidence(),
                null, record.getPushStatus(),
                suggestion == null ? "草稿已生成（AI 建议不可用），待人工复核后推送"
                        : "草稿已生成，待人工复核后推送");
    }

    /** AI 建议的复核意见呈现格式（一行业务摘要，完整字段留在审计事件明细里）。 */
    private static String formatSuggestion(AiAccountingSuggestionResponse s) {
        StringBuilder text = new StringBuilder("AI 建议：");
        if (s.businessCategory() != null) {
            text.append("业务类别 ").append(s.businessCategory());
        }
        if (s.suggestedSubject() != null) {
            text.append("｜科目 ").append(s.suggestedSubject());
        }
        if (s.suggestedSummary() != null) {
            text.append("｜摘要 ").append(s.suggestedSummary());
        }
        if (s.confidence() != null) {
            text.append("（置信度 ").append(Math.round(s.confidence() * 100)).append("%）");
        }
        return text.toString();
    }

    /**
     * 在「凭证草稿与制证」页对单条 PENDING 草稿重新生成 AI 建议（覆盖 reviewComment，
     * 不改任何状态字段）；已推送/已驳回/已通过的流水拒绝刷新。
     */
    @Transactional
    public AiAccountingSuggestionResponse refreshAiSuggestion(Long statementId, Long operatorId) {
        // W3（2026-09-18）：与制证口径对称——cross-company 权限用户可刷新他司草稿的 AI 建议。
        long companyId = companyScope.companyIdForUser(operatorId);
        boolean crossCompany = rbacService.permissionCodesForUser(operatorId)
                .contains("bankdata:cross-company:view");
        StatementRecord record = recordMapper.selectById(statementId);
        if (record == null || record.getCompanyId() == null
                || (record.getCompanyId() != companyId && !crossCompany)) {
            throw new BusinessException(404, "流水不存在或不在当前公司域内");
        }
        if ("PUSHED".equals(record.getPushStatus())) {
            throw new BusinessException(409, "已推送金蝶的流水不再刷新 AI 建议");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            throw new BusinessException(409, "已驳回的流水不刷新 AI 建议");
        }
        if (!REVIEW_PENDING.equals(record.getReviewStatus())) {
            throw new BusinessException(409, "仅待复核草稿可刷新 AI 建议");
        }
        AiAccountingSuggestionResponse suggestion = aiSuggestionService.suggest(statementId, operatorId);
        String comment = formatSuggestion(suggestion);
        int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewComment, comment)
                .set(StatementRecord::getAiSuggestionJson, serializeSuggestion(suggestion))
                .eq(StatementRecord::getId, statementId)
                .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
        if (updated != 1) {
            throw new BusinessException(409, "流水复核状态已变化，请刷新后重试");
        }
        insertAudit(record, "AI_SUGGESTION_REFRESH", "SUCCESS", REVIEW_PENDING, REVIEW_PENDING,
                operatorId, comment);
        return suggestion;
    }

    /**
     * V33 凭证草稿详情：保存人工在金蝶式单据页修正后的分录（AI 预填 → 人工复核+改）。
     *
     * <p>语义：
     * <ul>
     *   <li>仅未推送且未驳回的流水可改（PENDING/APPROVED 均可——推送前最后一刻也允许修正）；</li>
     *   <li>entries 必须借贷平衡（|Σ借-Σ贷| ≤ 0.01），科目名/方向/金额逐行校验；</li>
     *   <li>修正结果整体回写 ai_suggestion_json（edited/editedBy/editedAt 标记），
     *       人工主摘要同步回写 statement.summary——金蝶推送单据的 FREMARK/FCOMMENT 取自该字段
     *       （KingdeeBillPayloadBuilder 实证），保证「人工改了什么、金蝶就收什么」；</li>
     *   <li>复核意见追加「已人工修正」标记，审计 action=VOUCHER_DRAFT_EDIT。</li>
     * </ul></p>
     */
    @Transactional
    public VoucherSuggestionDto saveVoucherDraft(Long statementId, VoucherDraftSaveRequest request, Long operatorId) {
        // W3（2026-09-18）：与制证口径对称——cross-company 权限用户可修正他司凭证草稿。
        long companyId = companyScope.companyIdForUser(operatorId);
        boolean crossCompany = rbacService.permissionCodesForUser(operatorId)
                .contains("bankdata:cross-company:view");
        StatementRecord record = recordMapper.selectById(statementId);
        if (record == null || record.getCompanyId() == null
                || (record.getCompanyId() != companyId && !crossCompany)) {
            throw new BusinessException(404, "流水不存在或不在当前公司域内");
        }
        if ("PUSHED".equals(record.getPushStatus())) {
            throw new BusinessException(409, "已推送金蝶的凭证草稿不可再修改");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            throw new BusinessException(409, "已驳回的流水不可修改凭证草稿，请重新生成 AI 制证草稿");
        }
        List<VoucherEntry> entries = normalizeEntries(request == null ? null : request.entries());
        if (entries.isEmpty()) {
            throw new BusinessException(400, "凭证至少需要一条有效分录（科目名称/借贷方向/正数金额）");
        }
        if (!Boolean.TRUE.equals(VoucherSuggestionDto.isBalanced(entries))) {
            throw new BusinessException(400, "借贷不平衡：借方合计与贷方合计必须相等（允许 ±0.01）");
        }
        String mainSummary = request.summary() == null ? null : request.summary().trim();

        VoucherSuggestionDto base = parseSuggestionDoc(record.getAiSuggestionJson());
        VoucherSuggestionDto doc = new VoucherSuggestionDto(
                base == null ? null : base.businessCategory(),
                mainSummary != null && !mainSummary.isBlank() ? mainSummary
                        : (base == null ? null : base.suggestedSummary()),
                base == null ? null : base.counterpartyType(),
                base == null ? null : base.settlementMethod(),
                base == null ? null : base.suggestedSubject(),
                base == null ? null : base.riskNotes(),
                base == null ? null : base.confidence(),
                base == null ? null : base.rationale(),
                base == null ? null : base.model(),
                base == null ? null : base.durationMillis(),
                entries,
                Boolean.TRUE,
                true,
                operatorId,
                LocalDateTime.now());

        int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getAiSuggestionJson, serialize(doc))
                .set(mainSummary != null && !mainSummary.isBlank(), StatementRecord::getSummary, mainSummary)
                .set(StatementRecord::getReviewComment, appendEditedNote(record.getReviewComment()))
                .eq(StatementRecord::getId, statementId));
        if (updated != 1) {
            throw new BusinessException(409, "流水状态已变化，请刷新后重试");
        }
        insertAudit(record, "VOUCHER_DRAFT_EDIT", "SUCCESS", record.getReviewStatus(), record.getReviewStatus(),
                operatorId, "人工修正凭证分录（" + entries.size() + " 行）"
                        + (mainSummary != null && !mainSummary.isBlank()
                            ? "；主摘要更新为「" + mainSummary + "」（将随金蝶单据备注推送）" : ""));
        return doc;
    }

    /** 逐行校验并规范化人工输入：科目名/方向/金额必须有效，方向统一大写枚举。 */
    private List<VoucherEntry> normalizeEntries(List<VoucherDraftSaveRequest.EntryInput> inputs) {
        List<VoucherEntry> entries = new ArrayList<>();
        if (inputs == null) {
            return entries;
        }
        for (VoucherDraftSaveRequest.EntryInput input : inputs) {
            if (input == null) {
                continue;
            }
            String subjectName = input.subjectName() == null ? "" : input.subjectName().trim();
            String direction = input.direction() == null ? "" : input.direction().trim().toUpperCase(Locale.ROOT);
            BigDecimal amount = input.amount();
            if (subjectName.isBlank() || amount == null || amount.signum() <= 0
                    || (!VoucherEntry.DIRECTION_DEBIT.equals(direction)
                        && !VoucherEntry.DIRECTION_CREDIT.equals(direction))) {
                throw new BusinessException(400, "分录行无效：科目名称、借贷方向（DEBIT/CREDIT）与正数金额均为必填");
            }
            entries.add(new VoucherEntry(
                    input.summary() == null ? null : input.summary().trim(),
                    input.subjectCode() == null ? null : input.subjectCode().trim(),
                    subjectName,
                    direction,
                    amount,
                    input.confidence()));
        }
        return entries;
    }

    private VoucherSuggestionDto parseSuggestionDoc(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VoucherSuggestionDto.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeSuggestion(AiAccountingSuggestionResponse suggestion) {
        return serialize(VoucherSuggestionDto.from(suggestion));
    }

    private String serialize(VoucherSuggestionDto doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            // 序列化失败不该阻断草稿流程：降级为最小 JSON（AI 建议仍以 reviewComment 为准）。
            return null;
        }
    }

    private static String appendEditedNote(String comment) {
        String base = comment == null || comment.isBlank() ? "凭证分录已人工修正" : comment.trim();
        return base.contains("已人工修正") ? base : base + "｜已人工修正";
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

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value;
    }
}
