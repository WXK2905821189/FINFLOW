package com.finance.system.statement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.dto.VoucherEntry;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.dto.VoucherDraftSaveRequest;
import com.finance.system.statement.dto.VoucherSuggestionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 凭证草稿人工修正服务（V33，W16-A1 起从一键制证服务瘦身）。
 *
 * <p>W16-A1（2026-09-22 用户拍板「AI 制证退役」）：原「一键 AI 制证」编排职责
 * （createVouchers/processRow/pushGlRoute 等）已由 {@link BankDataPushService}
 * （规则引擎编排）取代并删除；AI 建议生成（refreshAiSuggestion）随 AI 制证链路
 * 一并退役。本服务保留<b>人工修正凭证草稿</b>单一职责——「预填 → 人工复核+改」
 * 交互中，人工改的那一半长期有效。</p>
 *
 * <p>{@link #saveVoucherDraft} 语义（V33）：
 * <ul>
 *   <li>仅未推送且未驳回的流水可改（PENDING/APPROVED 均可——推送前最后一刻也允许修正）；</li>
 *   <li>entries 必须借贷平衡（|Σ借-Σ贷| ≤ 0.01），科目名/方向/金额逐行校验；</li>
 *   <li>修正结果整体回写 ai_suggestion_json（edited/editedBy/editedAt 标记），
 *       人工主摘要同步回写 statement.summary——金蝶推送单据的 FREMARK/FCOMMENT 取自该字段
 *       （KingdeeBillPayloadBuilder 实证），保证「人工改了什么、金蝶就收什么」；</li>
 *   <li>复核意见追加「已人工修正」标记，审计 action=VOUCHER_DRAFT_EDIT。</li>
 * </ul></p>
 */
@Service
public class BankDataAccountingService {

    private static final String REVIEW_REJECTED = "REJECTED";

    private final StatementRecordMapper recordMapper;
    private final StatementAuditEventMapper auditEventMapper;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final ObjectMapper objectMapper;
    private final com.finance.system.closing.ClosingService closingService;

    public BankDataAccountingService(StatementRecordMapper recordMapper,
                                     StatementAuditEventMapper auditEventMapper,
                                     CompanyScopeService companyScope,
                                     RbacService rbacService,
                                     ObjectMapper objectMapper,
                                     com.finance.system.closing.ClosingService closingService) {
        this.recordMapper = recordMapper;
        this.auditEventMapper = auditEventMapper;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.objectMapper = objectMapper;
        this.closingService = closingService;
    }

    /**
     * V33 凭证草稿详情：保存人工在金蝶式单据页修正后的分录。
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
        if ("PUSHED".equals(record.getPushStatus()) || "GL_PUSHED".equals(record.getPushStatus())) {
            throw new BusinessException(409, "已推送金蝶的凭证草稿不可再修改");
        }
        if (REVIEW_REJECTED.equals(record.getReviewStatus())) {
            throw new BusinessException(409, "已驳回的流水不可修改凭证草稿，请重新制证");
        }
        // W7 账期锁：CLOSED 账期禁止修改凭证草稿（按流水所属公司+交易时间归月）。
        closingService.ensurePeriodOpen(record.getCompanyId(), record.getTransactionTime());
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

        int updated = recordMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StatementRecord>()
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

    private String serialize(VoucherSuggestionDto doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            // 序列化失败不该阻断草稿流程：降级为 null（复核意见仍是权威记录）。
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
}
