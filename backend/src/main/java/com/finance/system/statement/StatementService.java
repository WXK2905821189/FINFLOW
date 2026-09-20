package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.collector.StatementCollection;
import com.finance.system.statement.collector.StatementCollector;
import com.finance.system.statement.dto.StatementAuditEventResponse;
import com.finance.system.statement.dto.StatementBatchOpRequest;
import com.finance.system.statement.dto.StatementBatchOpResponse;
import com.finance.system.statement.dto.StatementBatchOpRowResult;
import com.finance.system.statement.dto.StatementBatchPushRequest;
import com.finance.system.statement.dto.StatementDashboardResponse;
import com.finance.system.statement.dto.StatementDetailResponse;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementImportRequest;
import com.finance.system.statement.dto.StatementRecordInput;
import com.finance.system.statement.dto.StatementResponse;
import com.finance.system.statement.dto.StatementReviewRequest;
import com.finance.system.statement.dto.StatementTransferRequest;
import com.finance.system.statement.dto.VoucherSuggestionDto;
import com.finance.system.statement.kingdee.KingdeeConnectionStatus;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.kingdee.KingdeeVoucherResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class StatementService extends ServiceImpl<StatementRecordMapper, StatementRecord> {

    private static final String VALID = "PASSED";
    private static final String INVALID = "FAILED";
    private static final String REVIEW_PENDING = "PENDING";
    private static final String REVIEW_APPROVED = "APPROVED";
    private static final String REVIEW_REJECTED = "REJECTED";
    /** V39（W10）：凭证撤回状态 —— 记录保留（追溯+审计），流水回池可重新制证。 */
    private static final String REVIEW_WITHDRAWN = "WITHDRAWN";
    /** 金蝶侧已成功接单的状态：置为此二者后不允许撤回。 */
    private static final java.util.Set<String> PUSH_COMPLETED = java.util.Set.of("PUSHED", "GL_PUSHED");
    private static final String PUSH_NOT_STARTED = "NOT_PUSHED";
    private static final String PUSH_PROCESSING = "PROCESSING";
    private static final String PUSHED = "PUSHED";

    private final StatementCollector collector;
    private final StatementImportBatchMapper batchMapper;
    private final StatementAuditEventMapper auditMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ObjectMapper objectMapper;
    private final KingdeeVoucherGateway kingdeeGateway;
    private final CompanyScopeService companyScope;
    private final BankDataStatementMapper bankDataStatementMapper;
    private final RbacService rbacService;
    private final com.finance.system.closing.ClosingService closingService;

    /** 与 bankdata 侧 BankDataQueryService 的跨公司权限码一致；转入他公司银行流水时要求。 */
    private static final String CROSS_COMPANY_PERMISSION = "bankdata:cross-company:view";

    public StatementService(StatementCollector collector,
                            StatementImportBatchMapper batchMapper,
                            StatementAuditEventMapper auditMapper,
                            BankAccountMapper bankAccountMapper,
                            ObjectMapper objectMapper,
                            KingdeeVoucherGateway kingdeeGateway,
                            CompanyScopeService companyScope,
                            BankDataStatementMapper bankDataStatementMapper,
                            RbacService rbacService,
                            com.finance.system.closing.ClosingService closingService) {
        this.collector = collector;
        this.batchMapper = batchMapper;
        this.auditMapper = auditMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.objectMapper = objectMapper;
        this.kingdeeGateway = kingdeeGateway;
        this.companyScope = companyScope;
        this.bankDataStatementMapper = bankDataStatementMapper;
        this.rbacService = rbacService;
        this.closingService = closingService;
    }

    @Transactional
    public StatementImportBatchResponse importBatch(StatementImportRequest request, Long operatorId) {
        long companyId = companyScope.companyIdForUser(operatorId);
        StatementCollection collection = collector.collect(request);
        // W7 账期锁：CLOSED 账期禁止导入流水（按流水交易时间归月，一次报清全部涉及月份）。
        closingService.ensurePeriodsOpen(companyId,
                collection.records().stream().map(StatementRecordInput::transactionTime).toList());
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("STB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        batch.setSourceType(collection.sourceType());
        batch.setSourceName(collection.sourceName());
        batch.setStatus("IMPORTING");
        batch.setTotalCount(collection.records().size());
        batch.setImportedCount(0);
        batch.setDuplicateCount(0);
        batch.setInvalidCount(0);
        batch.setCreatedBy(operatorId);
        batchMapper.insert(batch);

        int[] counters = processRecords(batch, collection.records(), companyId, operatorId);

        batch.setImportedCount(counters[0]);
        batch.setDuplicateCount(counters[1]);
        batch.setInvalidCount(counters[2]);
        batch.setStatus(counters[2] == 0 ? "COMPLETED" : "PARTIAL");
        batch.setCompletedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        return toBatchResponse(batch);
    }

    /**
     * 银行流水一键转入标准流水（银行数据模块 → 流水与入账）。
     *
     * <p>批次与标准流水按<b>银行流水行自身的公司归属</b>落库（而非操作者本公司），
     * 跨公司用户转入他公司流水时要求 {@code bankdata:cross-company:view}；单公司用户只能转入本公司流水。
     * 幂等：银行流水号（statementNo）作为标准流水的自然去重键，既有导入链路会自动计为 duplicate。</p>
     */
    @Transactional
    public StatementImportBatchResponse transferFromBankData(StatementTransferRequest request, Long operatorId) {
        List<Long> ids = request.statementIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请选择要转入的银行流水");
        }
        List<BankDataStatement> rows = bankDataStatementMapper.selectBatchIds(ids);
        if (rows.size() != ids.size()) {
            throw new BusinessException(404, "部分银行流水不存在或已被清理，请刷新后重试");
        }
        long ownCompanyId = companyScope.companyIdForUser(operatorId);
        Set<Long> rowCompanies = rows.stream().map(BankDataStatement::getCompanyId).collect(java.util.stream.Collectors.toSet());
        if (rowCompanies.size() > 1) {
            throw new BusinessException(400, "一次只能转入同一公司主体下的银行流水，请按公司分批选择");
        }
        long companyId = rowCompanies.iterator().next();
        if (companyId != ownCompanyId && !rbacService.permissionCodesForUser(operatorId).contains(CROSS_COMPANY_PERMISSION)) {
            throw new BusinessException(403, "没有转入其他公司主体流水的权限");
        }

        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("STB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        batch.setSourceType("BANKDATA");
        batch.setSourceName("银行流水转入 " + LocalDateTime.now().toLocalDate());
        batch.setStatus("IMPORTING");
        batch.setTotalCount(rows.size());
        batch.setImportedCount(0);
        batch.setDuplicateCount(0);
        batch.setInvalidCount(0);
        batch.setCreatedBy(operatorId);
        batchMapper.insert(batch);

        // W7 账期锁：CLOSED 账期禁止转入（公司归属取银行流水行自身，与批次公司一致）。
        closingService.ensurePeriodsOpen(companyId,
                rows.stream().map(BankDataStatement::getTransactionTime).toList());

        List<StatementRecordInput> inputs = rows.stream().map(this::mapToInput).toList();
        int[] counters = processRecords(batch, inputs, companyId, operatorId);

        batch.setImportedCount(counters[0]);
        batch.setDuplicateCount(counters[1]);
        batch.setInvalidCount(counters[2]);
        batch.setStatus(counters[2] == 0 ? "COMPLETED" : "PARTIAL");
        batch.setCompletedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        return toBatchResponse(batch);
    }

    /**
     * 银行流水行 → 标准流水输入。对手方名称与摘要为标准流水的必填字段，按银行原生字段链兜底
     * （业务名称 / 你方摘要 / 扩展摘要 / 网银摘要）；对手方账号使用脱敏值，完整账号始终只在原始报文留存。
     */
    private StatementRecordInput mapToInput(BankDataStatement row) {
        String counterpartyName = firstNonBlank(row.getCounterpartyName(), row.getBusinessName(), "银行交易");
        String summary = firstNonBlank(row.getSummary(), row.getRemarkTextClt(), row.getExtendedRemark(),
                row.getBusinessText(), row.getBusinessName(), "银行流水 " + row.getStatementNo());
        String statementNo = firstNonBlank(row.getStatementNo(), "BKD-" + row.getId());
        return new StatementRecordInput(statementNo, row.getBankAccountId(), row.getTransactionTime(),
                row.getDirection(), row.getAmount(), row.getCurrency(),
                counterpartyName, row.getCounterpartyAccountMasked(), summary);
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    /** 导入/转入共用的落库循环：返回 {imported, duplicates, invalid}。 */
    private int[] processRecords(StatementImportBatch batch, List<StatementRecordInput> records, long companyId, Long operatorId) {
        int imported = 0;
        int duplicates = 0;
        int invalid = 0;
        Set<String> batchStatementNumbers = new HashSet<>();
        for (StatementRecordInput input : records) {
            String statementNo = normalizeStatementNo(input.statementNo());
            if (!batchStatementNumbers.add(statementNo)
                    || baseMapper.selectCount(new LambdaQueryWrapper<StatementRecord>()
                    .eq(StatementRecord::getCompanyId, companyId)
                    .eq(StatementRecord::getStatementNo, statementNo)) > 0) {
                duplicates++;
                continue;
            }

            String validationMessage = validate(input, companyId);
            StatementRecord statement = toEntity(input, statementNo, batch.getId(), companyId, validationMessage);
            baseMapper.insert(statement);
            imported++;
            if (!validationMessage.isBlank()) {
                invalid++;
            }
            audit(statement, "IMPORT", "SUCCESS", null,
                    statement.getValidationStatus(), operatorId, validationMessage);
        }
        return new int[]{imported, duplicates, invalid};
    }

    public PageResponse<StatementResponse> pageStatements(int page, int size, String validationStatus,
                                                            String reviewStatus, String pushStatus, Long userId) {
        CompanyView view = viewFor(userId);
        LambdaQueryWrapper<StatementRecord> query = new LambdaQueryWrapper<StatementRecord>()
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId())
                .eq(validationStatus != null && !validationStatus.isBlank(), StatementRecord::getValidationStatus, validationStatus)
                .eq(reviewStatus != null && !reviewStatus.isBlank(), StatementRecord::getReviewStatus, reviewStatus)
                .eq(pushStatus != null && !pushStatus.isBlank(), StatementRecord::getPushStatus, pushStatus)
                .orderByDesc(StatementRecord::getTransactionTime)
                .orderByDesc(StatementRecord::getId);
        IPage<StatementRecord> result = page(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toResponse).toList());
    }

    public StatementDetailResponse getDetail(Long id, Long userId) {
        CompanyView view = viewFor(userId);
        StatementRecord statement = require(id, view);
        List<StatementAuditEventResponse> trail = auditMapper.selectList(new LambdaQueryWrapper<StatementAuditEvent>()
                        .eq(!view.crossCompany(), StatementAuditEvent::getCompanyId, view.ownCompanyId())
                        .eq(StatementAuditEvent::getStatementId, id)
                        .orderByAsc(StatementAuditEvent::getCreatedAt)
                        .orderByAsc(StatementAuditEvent::getId))
                .stream().map(this::toAuditResponse).toList();
        return new StatementDetailResponse(toResponse(statement), parseAiSuggestion(statement), trail);
    }

    /**
     * V33：解析 statement_record.ai_suggestion_json 为凭证建议文档；损坏/缺失一律返回 null
     * （详情页展示「AI 建议不可用」，绝不因 JSON 异常打断详情加载）。
     */
    private VoucherSuggestionDto parseAiSuggestion(StatementRecord statement) {
        String json = statement.getAiSuggestionJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VoucherSuggestionDto.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public StatementResponse review(Long id, StatementReviewRequest request, Long operatorId) {
        CompanyView view = viewFor(operatorId);
        return reviewInternal(id, view, request.action(), request.comment(), operatorId);
    }

    /**
     * 复核核心（单条与批量共用）。职责分离规则（2026-09-17 调整）：导入者不能复核自己，
     * 但 BANKDATA 批次（AI 草稿链路）例外——草稿生成与人工复核常为同一管理员，
     * 审计事件（REVIEW_APPROVE/REJECT）仍完整记录操作人。
     * W3（2026-09-18）：公司域从「操作者本公司」放宽为 {@link CompanyView} 可见域。
     */
    private StatementResponse reviewInternal(Long id, CompanyView view, String action, String comment,
                                             Long operatorId) {
        StatementRecord existing = require(id, view);
        StatementImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<StatementImportBatch>()
                .eq(StatementImportBatch::getId, existing.getBatchId())
                .eq(!view.crossCompany(), StatementImportBatch::getCompanyId, view.ownCompanyId()));
        if (batch != null && java.util.Objects.equals(batch.getCreatedBy(), operatorId)
                && !"BANKDATA".equals(batch.getSourceType())) {
            throw new BusinessException(403, "Importers cannot review their own statements");
        }
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalizedAction) && !"REJECT".equals(normalizedAction)) {
            throw new BusinessException(400, "Review action must be APPROVE or REJECT");
        }
        if (REVIEW_APPROVED.equals(existing.getReviewStatus()) || REVIEW_REJECTED.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "Statement has already been reviewed");
        }
        if ("APPROVE".equals(normalizedAction) && !VALID.equals(existing.getValidationStatus())) {
            throw new BusinessException(409, "Only validated statements can be approved");
        }
        if ("REJECT".equals(normalizedAction) && (comment == null || comment.isBlank())) {
            throw new BusinessException(400, "A rejection comment is required");
        }
        String nextStatus = "APPROVE".equals(normalizedAction) ? REVIEW_APPROVED : REVIEW_REJECTED;
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, nextStatus)
                .set(StatementRecord::getReviewComment, trimToNull(comment))
                .set(StatementRecord::getReviewedBy, operatorId)
                .set(StatementRecord::getReviewedAt, LocalDateTime.now())
                .eq(StatementRecord::getId, id)
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId())
                .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
        if (updated != 1) {
            throw new BusinessException(409, "Statement review status has changed");
        }
        StatementRecord result = require(id, view);
        audit(result, "REVIEW_" + normalizedAction, "SUCCESS", REVIEW_PENDING, result.getReviewStatus(), operatorId,
                trimToNull(comment));
        return toResponse(result);
    }

    /**
     * 批量复核（「凭证草稿与制证」工作台：批量通过 / 批量驳回）。逐行容错：
     * 单行失败不影响其余行，409（已复核/状态变化）归类 SKIPPED，其余异常归类 FAILED。
     */
    @Transactional
    public StatementBatchOpResponse batchReview(StatementBatchOpRequest request, Long operatorId) {
        String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            throw new BusinessException(400, "Review action must be APPROVE or REJECT");
        }
        if ("REJECT".equals(action) && (request.comment() == null || request.comment().isBlank())) {
            throw new BusinessException(400, "A rejection comment is required");
        }
        CompanyView view = viewFor(operatorId);
        List<StatementBatchOpRowResult> rows = new ArrayList<>();
        for (Long id : request.ids().stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                StatementResponse record = reviewInternal(id, view, action, request.comment(), operatorId);
                rows.add(new StatementBatchOpRowResult(id, record.statementNo(),
                        "APPROVE".equals(action) ? "APPROVED" : "REJECTED", record.voucherNo(),
                        "APPROVE".equals(action) ? "已通过复核，可推送金蝶" : "已驳回"));
            } catch (BusinessException e) {
                rows.add(failureRow(id, view, e.getCode() == 409 ? "SKIPPED" : "FAILED", e.getMessage()));
            }
        }
        return summarize(rows);
    }

    /**
     * 批量推送（「凭证草稿与制证」工作台：把已通过复核的草稿推送金蝶）。逐行容错，
     * 预检已推送（幂等跳过）与未满足复核前置（跳过），推送失败保留服务端 pushMessage。
     */
    @Transactional
    public StatementBatchOpResponse batchPush(StatementBatchPushRequest request, Long operatorId) {
        CompanyView view = viewFor(operatorId);
        List<StatementBatchOpRowResult> rows = new ArrayList<>();
        for (Long id : request.ids().stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                StatementRecord record = require(id, view);
                if (PUSHED.equals(record.getPushStatus())) {
                    rows.add(new StatementBatchOpRowResult(id, record.getStatementNo(), "ALREADY_PUSHED",
                            record.getVoucherNo(), "此前已推送金蝶（幂等跳过）"));
                    continue;
                }
                if (!VALID.equals(record.getValidationStatus()) || !REVIEW_APPROVED.equals(record.getReviewStatus())) {
                    rows.add(new StatementBatchOpRowResult(id, record.getStatementNo(), "SKIPPED", null,
                            "仅校验通过且已复核的流水可推送（当前 " + record.getValidationStatus()
                                    + "/" + record.getReviewStatus() + "）"));
                    continue;
                }
                StatementResponse pushed = pushVoucher(id, operatorId);
                if (PUSHED.equals(pushed.pushStatus())) {
                    rows.add(new StatementBatchOpRowResult(id, pushed.statementNo(), "PUSHED", pushed.voucherNo(),
                            pushed.pushMessage() == null ? "已推送金蝶" : pushed.pushMessage()));
                } else {
                    rows.add(new StatementBatchOpRowResult(id, pushed.statementNo(), "FAILED", pushed.voucherNo(),
                            pushed.pushMessage() == null ? "推送失败" : pushed.pushMessage()));
                }
            } catch (BusinessException e) {
                rows.add(failureRow(id, view, e.getCode() == 409 ? "SKIPPED" : "FAILED", e.getMessage()));
            }
        }
        return summarize(rows);
    }

    /**
     * W8（2026-09-20）：重新打开已驳回的流水（REJECTED → PENDING），使其可重新制证。
     * 原语义「驳回=终态」是为了防篡改；本端点提供**显式、留痕**的重开动作（仅凭证推送权限者，
     * 端点层 @PreAuthorize 控制），审计 REOPEN 完整记录操作人。上次的驳回意见保留，
     * 便于重开后的复核人看到驳回原因。重开后可直接走既有制证链路（草稿/一键制证）。
     */
    @Transactional
    public StatementResponse reopen(Long id, Long operatorId) {
        CompanyView view = viewFor(operatorId);
        StatementRecord existing = require(id, view);
        if (!REVIEW_REJECTED.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "仅已驳回的流水可重新打开（当前 " + existing.getReviewStatus() + "）");
        }
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, REVIEW_PENDING)
                .eq(StatementRecord::getId, id)
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId())
                .eq(StatementRecord::getReviewStatus, REVIEW_REJECTED));
        if (updated != 1) {
            throw new BusinessException(409, "Statement review status has changed");
        }
        StatementRecord result = require(id, view);
        audit(result, "REOPEN", "SUCCESS", REVIEW_REJECTED, result.getReviewStatus(), operatorId,
                "已驳回流水重新打开，可重新制证");
        return toResponse(result);
    }

    /**
     * V39（W10）：凭证撤回 —— 未成功推送金蝶（push_status ∉ {PUSHED, GL_PUSHED}）的凭证可撤回。
     *
     * <p>撤回 = review_status 置 {@link #REVIEW_WITHDRAWN}，记录与凭证号保留（追溯），
     * withdrawn_at / withdrawn_by 记录操作痕迹，审计落 WITHDRAW；银行数据层的「已转入」判定
     * 排除 WITHDRAWN → 流水立即回池、重新可选，重新制证时同一记录被复活为 PENDING
     * （statement_record 有 uk_statement_record_company_no 唯一约束，不会产生重复记录）。</p>
     *
     * <p>条件更新只用主键 + 公司域：review_status 可能为 null（历史行），用 {@code ne} 会因
     * SQL 三值逻辑（NULL &lt;&gt; 'WITHDRAWN' 为 UNKNOWN）静默不匹配，因此状态校验放在读后判断。</p>
     */
    @Transactional
    public StatementResponse withdraw(Long id, Long operatorId) {
        CompanyView view = viewFor(operatorId);
        StatementRecord existing = require(id, view);
        if (REVIEW_WITHDRAWN.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "该凭证已撤回，无需重复操作");
        }
        if (PUSH_COMPLETED.contains(existing.getPushStatus())) {
            throw new BusinessException(409, "已推送金蝶的凭证不可撤回（请在金蝶侧处理）");
        }
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, REVIEW_WITHDRAWN)
                .set(StatementRecord::getWithdrawnAt, LocalDateTime.now())
                .set(StatementRecord::getWithdrawnBy, operatorId)
                .eq(StatementRecord::getId, id)
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId()));
        if (updated != 1) {
            throw new BusinessException(409, "凭证状态已变化，请刷新后重试");
        }
        StatementRecord result = require(id, view);
        audit(result, "WITHDRAW", "SUCCESS", existing.getReviewStatus(), REVIEW_WITHDRAWN, operatorId,
                "撤回凭证（未推送金蝶），流水回池可重新制证");
        return toResponse(result);
    }

    private StatementBatchOpRowResult failureRow(Long id, CompanyView view, String outcome, String message) {
        String statementNo = null;
        try {
            statementNo = require(id, view).getStatementNo();
        } catch (BusinessException ignored) {
            // 流水不可见（404）：statementNo 置空，仍回报该行失败原因。
        }
        return new StatementBatchOpRowResult(id, statementNo, outcome, null, message);
    }

    private StatementBatchOpResponse summarize(List<StatementBatchOpRowResult> rows) {
        return new StatementBatchOpResponse(rows.size(),
                (int) rows.stream().filter(r -> "APPROVED".equals(r.outcome())
                        || "REJECTED".equals(r.outcome()) || "PUSHED".equals(r.outcome())).count(),
                (int) rows.stream().filter(r -> "SKIPPED".equals(r.outcome())
                        || "ALREADY_PUSHED".equals(r.outcome())).count(),
                (int) rows.stream().filter(r -> "FAILED".equals(r.outcome())).count(),
                rows);
    }

    /**
     * Read-only Kingdee connectivity probe for the UI connection-test button.
     * Delegates to the active gateway (mock/unavailable/real); never touches statements.
     */
    public KingdeeConnectionStatus pingKingdee() {
        return kingdeeGateway.ping();
    }

    @Transactional
    public StatementResponse pushVoucher(Long id, Long operatorId) {
        CompanyView view = viewFor(operatorId);
        StatementRecord existing = require(id, view);
        if (!VALID.equals(existing.getValidationStatus()) || !REVIEW_APPROVED.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "Only validated and approved statements can be pushed");
        }
        if (PUSHED.equals(existing.getPushStatus())) {
            return toResponse(existing);
        }
        // W7 账期锁：CLOSED 账期禁止推送（按流水所属公司+交易时间归月；批量链路 409 转为行级 SKIPPED）。
        closingService.ensurePeriodOpen(existing.getCompanyId(), existing.getTransactionTime());
        String previousPushStatus = existing.getPushStatus();
        int claimed = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getPushStatus, PUSH_PROCESSING)
                .eq(StatementRecord::getId, id)
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId())
                .in(StatementRecord::getPushStatus, PUSH_NOT_STARTED, "FAILED"));
        if (claimed != 1) {
            throw new BusinessException(409, "Statement push is already in progress or has completed");
        }

        StatementRecord processing = require(id, view);
        KingdeeVoucherResult result = kingdeeGateway.push(processing);
        if (!PUSHED.equalsIgnoreCase(result.status())) {
            baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                    .set(StatementRecord::getPushStatus, "FAILED")
                    .set(StatementRecord::getPushMessage, trimToNull(result.message()))
                    .eq(StatementRecord::getId, id)
                    .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId()));
            StatementRecord failed = require(id, view);
            audit(failed, "PUSH_VOUCHER", "FAILED", previousPushStatus, failed.getPushStatus(), operatorId,
                    failed.getPushMessage());
            return toResponse(failed);
        }
        baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getPushStatus, PUSHED)
                .set(StatementRecord::getVoucherNo, result.voucherNo())
                .set(StatementRecord::getPushMessage, trimToNull(result.message()))
                .set(StatementRecord::getPushedAt, LocalDateTime.now())
                .eq(StatementRecord::getId, id)
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId())
                .eq(StatementRecord::getPushStatus, PUSH_PROCESSING));
        StatementRecord pushed = require(id, view);
        audit(pushed, "PUSH_VOUCHER", "SUCCESS", previousPushStatus, pushed.getPushStatus(), operatorId,
                pushed.getVoucherNo());
        return toResponse(pushed);
    }

    public PageResponse<StatementImportBatchResponse> pageBatches(int page, int size, Long userId) {
        CompanyView view = viewFor(userId);
        Page<StatementImportBatch> result = batchMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<StatementImportBatch>()
                        .eq(!view.crossCompany(), StatementImportBatch::getCompanyId, view.ownCompanyId())
                        .orderByDesc(StatementImportBatch::getCreatedAt)
                        .orderByDesc(StatementImportBatch::getId));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toBatchResponse).toList());
    }

    public StatementImportBatchResponse getBatch(Long id, Long userId) {
        CompanyView view = viewFor(userId);
        StatementImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<StatementImportBatch>()
                .eq(StatementImportBatch::getId, id)
                .eq(!view.crossCompany(), StatementImportBatch::getCompanyId, view.ownCompanyId()));
        if (batch == null) {
            throw new BusinessException(404, "Import batch not found");
        }
        return toBatchResponse(batch);
    }

    public StatementDashboardResponse dashboard(Long userId) {
        CompanyView view = viewFor(userId);
        List<StatementRecord> records = list(new LambdaQueryWrapper<StatementRecord>()
                .eq(!view.crossCompany(), StatementRecord::getCompanyId, view.ownCompanyId()));
        long pending = records.stream().filter(s -> REVIEW_PENDING.equals(s.getReviewStatus())).count();
        long approved = records.stream().filter(s -> REVIEW_APPROVED.equals(s.getReviewStatus())).count();
        long rejected = records.stream().filter(s -> REVIEW_REJECTED.equals(s.getReviewStatus())).count();
        long pushed = records.stream().filter(s -> PUSHED.equals(s.getPushStatus())).count();
        long invalid = records.stream().filter(s -> INVALID.equals(s.getValidationStatus())).count();
        BigDecimal totalAmount = sum(records, null, null);
        BigDecimal approvedAmount = sum(records, REVIEW_APPROVED, null);
        BigDecimal pushedAmount = sum(records, null, PUSHED);
        return new StatementDashboardResponse(records.size(), pending, approved, rejected, pushed, invalid,
                totalAmount, approvedAmount, pushedAmount);
    }

    private String validate(StatementRecordInput input, long companyId) {
        List<String> errors = new ArrayList<>();
        if (input.statementNo() == null || input.statementNo().isBlank()) errors.add("statementNo is required");
        else if (input.statementNo().trim().length() > 128) errors.add("statementNo is too long");
        if (input.bankAccountId() == null) errors.add("bankAccountId is required");
        else {
            BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                    .eq(BankAccount::getId, input.bankAccountId())
                    .eq(BankAccount::getCompanyId, companyId));
            if (account == null) errors.add("bankAccountId does not exist");
        }
        if (input.transactionTime() == null) errors.add("transactionTime is required");
        if (input.direction() == null || !("INCOME".equalsIgnoreCase(input.direction()) || "EXPENSE".equalsIgnoreCase(input.direction()))) {
            errors.add("direction must be INCOME or EXPENSE");
        }
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) errors.add("amount must be greater than zero");
        else if (input.amount().scale() > 2) errors.add("amount must have at most two decimal places");
        if (input.currency() != null && !"CNY".equalsIgnoreCase(input.currency())) errors.add("currency must be CNY");
        if (input.counterpartyName() == null || input.counterpartyName().isBlank()) errors.add("counterpartyName is required");
        else if (input.counterpartyName().length() > 128) errors.add("counterpartyName is too long");
        if (input.counterpartyAccount() != null && input.counterpartyAccount().length() > 128) errors.add("counterpartyAccount is too long");
        if (input.summary() == null || input.summary().isBlank()) errors.add("summary is required");
        else if (input.summary().length() > 255) errors.add("summary is too long");
        return String.join("; ", errors);
    }

    private StatementRecord toEntity(StatementRecordInput input, String statementNo, Long batchId, long companyId,
                                     String validationMessage) {
        StatementRecord statement = new StatementRecord();
        statement.setCompanyId(companyId);
        statement.setBatchId(batchId);
        statement.setStatementNo(statementNo);
        statement.setBankAccountId(input.bankAccountId());
        statement.setTransactionTime(input.transactionTime());
        statement.setDirection(normalize(input.direction()));
        statement.setAmount(input.amount() == null || input.amount().scale() > 2 ? null
                : input.amount().setScale(2, RoundingMode.UNNECESSARY));
        statement.setCurrency(input.currency() == null || input.currency().isBlank() ? "CNY" : input.currency().trim().toUpperCase(Locale.ROOT));
        statement.setCounterpartyName(trimToNull(input.counterpartyName()));
        statement.setCounterpartyAccount(maskAccount(input.counterpartyAccount()));
        statement.setSummary(trimToNull(input.summary()));
        statement.setRawPayload(rawPayload(input));
        statement.setValidationStatus(validationMessage.isBlank() ? VALID : INVALID);
        statement.setValidationMessage(trimToNull(validationMessage));
        statement.setReviewStatus(REVIEW_PENDING);
        statement.setPushStatus(PUSH_NOT_STARTED);
        return statement;
    }

    private void audit(StatementRecord statement, String action, String result, String previous, String current,
                       Long operatorId, String detail) {
        StatementAuditEvent event = new StatementAuditEvent();
        event.setCompanyId(statement.getCompanyId());
        event.setStatementId(statement.getId());
        event.setBatchId(statement.getBatchId());
        event.setAction(action);
        event.setResult(result);
        event.setPreviousStatus(previous);
        event.setCurrentStatus(current);
        event.setOperatorId(operatorId);
        event.setDetail(trimToNull(detail));
        auditMapper.insert(event);
    }

    /**
     * W3（2026-09-18）凭证链路可见域：本公司恒可见；持 {@code bankdata:cross-company:view}
     * 权限者可见全部公司主体——与制证/转入口径（transferFromBankData 153 行）对称。
     * 修复「cross-company 用户对他司流水 AI 制证为草稿成功，但凭证中心/复核/推送全程 404 不可见」。
     */
    record CompanyView(long ownCompanyId, boolean crossCompany) {
        boolean canAccess(Long rowCompanyId) {
            return crossCompany || (rowCompanyId != null && rowCompanyId == ownCompanyId);
        }
    }

    private CompanyView viewFor(Long userId) {
        long own = companyScope.companyIdForUser(userId);
        boolean cross = rbacService.permissionCodesForUser(userId).contains(CROSS_COMPANY_PERMISSION);
        return new CompanyView(own, cross);
    }

    private StatementRecord require(Long id, CompanyView view) {
        StatementRecord statement = getById(id);
        if (statement == null || !view.canAccess(statement.getCompanyId())) {
            throw new BusinessException(404, "Statement not found");
        }
        return statement;
    }

    private String normalizeStatementNo(String value) {
        if (value == null || value.isBlank()) {
            return "INVALID-" + UUID.randomUUID().toString().replace("-", "");
        }
        return value.trim();
    }

    private String rawPayload(StatementRecordInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "Statement payload cannot be serialized");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String maskAccount(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    private BigDecimal sum(List<StatementRecord> records, String reviewStatus, String pushStatus) {
        return records.stream()
                .filter(s -> VALID.equals(s.getValidationStatus()))
                .filter(s -> reviewStatus == null || reviewStatus.equals(s.getReviewStatus()))
                .filter(s -> pushStatus == null || pushStatus.equals(s.getPushStatus()))
                .map(StatementRecord::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.UNNECESSARY);
    }

    private StatementResponse toResponse(StatementRecord s) {
        return new StatementResponse(s.getId(), s.getBatchId(), s.getStatementNo(), s.getBankAccountId(),
                s.getTransactionTime(), s.getDirection(), s.getAmount(), s.getCurrency(), s.getCounterpartyName(),
                s.getCounterpartyAccount(), s.getSummary(), s.getValidationStatus(), s.getValidationMessage(),
                s.getReviewStatus(), s.getReviewComment(), s.getReviewedBy(), s.getReviewedAt(), s.getPushStatus(),
                s.getVoucherNo(), s.getPushMessage(), s.getPushedAt(), s.getCreatedAt());
    }

    private StatementImportBatchResponse toBatchResponse(StatementImportBatch b) {
        return new StatementImportBatchResponse(b.getId(), b.getBatchNo(), b.getSourceType(), b.getSourceName(),
                b.getStatus(), b.getTotalCount(), b.getImportedCount(), b.getDuplicateCount(), b.getInvalidCount(),
                b.getCreatedBy(), b.getCreatedAt(), b.getCompletedAt(), b.getErrorMessage());
    }

    private StatementAuditEventResponse toAuditResponse(StatementAuditEvent e) {
        return new StatementAuditEventResponse(e.getId(), e.getAction(), e.getResult(), e.getPreviousStatus(),
                e.getCurrentStatus(), e.getOperatorId(), e.getDetail(), e.getCreatedAt());
    }
}
