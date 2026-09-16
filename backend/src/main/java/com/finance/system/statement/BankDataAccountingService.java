package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.ai.AccountingSuggestionService;
import com.finance.system.ai.dto.AiAccountingSuggestionResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一键 AI 制证（2026-09-16 决策）：银行流水行 → 转入标准流水（幂等）→ AI 入账建议 →
 * 复核闸门内化 → 推送金蝶（save→submit，audit 留给金蝶侧人工审核）。
 *
 * <p>流程决策记录：
 * <ul>
 *   <li>复用 {@link StatementService#transferFromBankData} 完成转入（含跨公司权限、批次、
 *   查重与 IMPORT 审计），本服务不重复实现转入逻辑；</li>
 *   <li>复核不再要求「导入者不能复核自己」——一键链路中复核职责转移到金蝶侧人工审核，
 *   系统内自动置 APPROVED 并留审计事件（action=AI_VOUCHER_REVIEW）说明 AI 建议与人工审核落点；</li>
 *   <li>AI 建议失败（未配置/无 ai:use 权限/限频/解析失败）时降级继续推送：AI 是增强不是闸门，
 *   金蝶侧人工审核兜底，逐行结果以 aiStatus 标注；</li>
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

    public BankDataAccountingService(BankDataStatementMapper bankDataStatementMapper,
                                     BankAccountMapper bankAccountMapper,
                                     StatementRecordMapper recordMapper,
                                     StatementAuditEventMapper auditEventMapper,
                                     StatementService statementService,
                                     AccountingSuggestionService aiSuggestionService,
                                     CompanyScopeService companyScope,
                                     RbacService rbacService) {
        this.bankDataStatementMapper = bankDataStatementMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.recordMapper = recordMapper;
        this.auditEventMapper = auditEventMapper;
        this.statementService = statementService;
        this.aiSuggestionService = aiSuggestionService;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
    }

    @Transactional
    public AiVoucherBatchResponse createVouchers(List<Long> statementIds, Long operatorId) {
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

        // 逐行：AI 建议 → 自动复核 → 推送。
        for (BankDataStatement row : eligible) {
            results.put(row.getId(), processRow(row, operatorId));
        }

        List<AiVoucherRowResult> ordered = ids.stream().map(results::get)
                .filter(Objects::nonNull).toList();
        return new AiVoucherBatchResponse(
                batchNoHolder[0],
                ordered.size(),
                (int) ordered.stream().filter(r -> "PUSHED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> "ALREADY_PUSHED".equals(r.outcome())).count(),
                (int) ordered.stream().filter(r -> r.outcome() != null && r.outcome().startsWith("SKIPPED")).count(),
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
