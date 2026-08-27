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
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.bankdata.scope.CompanyScopeService;
import com.finance.system.statement.collector.StatementCollection;
import com.finance.system.statement.collector.StatementCollector;
import com.finance.system.statement.dto.StatementAuditEventResponse;
import com.finance.system.statement.dto.StatementDashboardResponse;
import com.finance.system.statement.dto.StatementDetailResponse;
import com.finance.system.statement.dto.StatementImportBatchResponse;
import com.finance.system.statement.dto.StatementImportRequest;
import com.finance.system.statement.dto.StatementRecordInput;
import com.finance.system.statement.dto.StatementResponse;
import com.finance.system.statement.dto.StatementReviewRequest;
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
import java.util.Set;
import java.util.UUID;

@Service
public class StatementService extends ServiceImpl<StatementRecordMapper, StatementRecord> {

    private static final String VALID = "PASSED";
    private static final String INVALID = "FAILED";
    private static final String REVIEW_PENDING = "PENDING";
    private static final String REVIEW_APPROVED = "APPROVED";
    private static final String REVIEW_REJECTED = "REJECTED";
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

    public StatementService(StatementCollector collector,
                            StatementImportBatchMapper batchMapper,
                            StatementAuditEventMapper auditMapper,
                            BankAccountMapper bankAccountMapper,
                            ObjectMapper objectMapper,
                            KingdeeVoucherGateway kingdeeGateway,
                            CompanyScopeService companyScope) {
        this.collector = collector;
        this.batchMapper = batchMapper;
        this.auditMapper = auditMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.objectMapper = objectMapper;
        this.kingdeeGateway = kingdeeGateway;
        this.companyScope = companyScope;
    }

    @Transactional
    public StatementImportBatchResponse importBatch(StatementImportRequest request, Long operatorId) {
        long companyId = companyScope.companyIdForUser(operatorId);
        StatementCollection collection = collector.collect(request);
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

        int imported = 0;
        int duplicates = 0;
        int invalid = 0;
        Set<String> batchStatementNumbers = new HashSet<>();
        for (StatementRecordInput input : collection.records()) {
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

        batch.setImportedCount(imported);
        batch.setDuplicateCount(duplicates);
        batch.setInvalidCount(invalid);
        batch.setStatus(invalid == 0 ? "COMPLETED" : "PARTIAL");
        batch.setCompletedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        return toBatchResponse(batch);
    }

    public PageResponse<StatementResponse> pageStatements(int page, int size, String validationStatus,
                                                            String reviewStatus, String pushStatus, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<StatementRecord> query = new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, companyId)
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
        long companyId = companyScope.companyIdForUser(userId);
        StatementRecord statement = require(id, companyId);
        List<StatementAuditEventResponse> trail = auditMapper.selectList(new LambdaQueryWrapper<StatementAuditEvent>()
                        .eq(StatementAuditEvent::getCompanyId, companyId)
                        .eq(StatementAuditEvent::getStatementId, id)
                        .orderByAsc(StatementAuditEvent::getCreatedAt)
                        .orderByAsc(StatementAuditEvent::getId))
                .stream().map(this::toAuditResponse).toList();
        return new StatementDetailResponse(toResponse(statement), trail);
    }

    @Transactional
    public StatementResponse review(Long id, StatementReviewRequest request, Long operatorId) {
        long companyId = companyScope.companyIdForUser(operatorId);
        StatementRecord existing = require(id, companyId);
        StatementImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<StatementImportBatch>()
                .eq(StatementImportBatch::getId, existing.getBatchId())
                .eq(StatementImportBatch::getCompanyId, companyId));
        if (batch != null && java.util.Objects.equals(batch.getCreatedBy(), operatorId)) {
            throw new BusinessException(403, "Importers cannot review their own statements");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            throw new BusinessException(400, "Review action must be APPROVE or REJECT");
        }
        if (REVIEW_APPROVED.equals(existing.getReviewStatus()) || REVIEW_REJECTED.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "Statement has already been reviewed");
        }
        if ("APPROVE".equals(action) && !VALID.equals(existing.getValidationStatus())) {
            throw new BusinessException(409, "Only validated statements can be approved");
        }
        if ("REJECT".equals(action) && (request.comment() == null || request.comment().isBlank())) {
            throw new BusinessException(400, "A rejection comment is required");
        }
        String nextStatus = "APPROVE".equals(action) ? REVIEW_APPROVED : REVIEW_REJECTED;
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getReviewStatus, nextStatus)
                .set(StatementRecord::getReviewComment, trimToNull(request.comment()))
                .set(StatementRecord::getReviewedBy, operatorId)
                .set(StatementRecord::getReviewedAt, LocalDateTime.now())
                .eq(StatementRecord::getId, id)
                .eq(StatementRecord::getCompanyId, companyId)
                .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));
        if (updated != 1) {
            throw new BusinessException(409, "Statement review status has changed");
        }
        StatementRecord result = require(id, companyId);
        audit(result, "REVIEW_" + action, "SUCCESS", REVIEW_PENDING, result.getReviewStatus(), operatorId,
                trimToNull(request.comment()));
        return toResponse(result);
    }

    @Transactional
    public StatementResponse pushVoucher(Long id, Long operatorId) {
        long companyId = companyScope.companyIdForUser(operatorId);
        StatementRecord existing = require(id, companyId);
        if (!VALID.equals(existing.getValidationStatus()) || !REVIEW_APPROVED.equals(existing.getReviewStatus())) {
            throw new BusinessException(409, "Only validated and approved statements can be pushed");
        }
        if (PUSHED.equals(existing.getPushStatus())) {
            return toResponse(existing);
        }
        String previousPushStatus = existing.getPushStatus();
        int claimed = baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                .set(StatementRecord::getPushStatus, PUSH_PROCESSING)
                .eq(StatementRecord::getId, id)
                .eq(StatementRecord::getCompanyId, companyId)
                .in(StatementRecord::getPushStatus, PUSH_NOT_STARTED, "FAILED"));
        if (claimed != 1) {
            throw new BusinessException(409, "Statement push is already in progress or has completed");
        }

        StatementRecord processing = require(id, companyId);
        KingdeeVoucherResult result = kingdeeGateway.push(processing);
        if (!PUSHED.equalsIgnoreCase(result.status())) {
            baseMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
                    .set(StatementRecord::getPushStatus, "FAILED")
                    .set(StatementRecord::getPushMessage, trimToNull(result.message()))
                    .eq(StatementRecord::getId, id)
                    .eq(StatementRecord::getCompanyId, companyId));
            StatementRecord failed = require(id, companyId);
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
                .eq(StatementRecord::getCompanyId, companyId)
                .eq(StatementRecord::getPushStatus, PUSH_PROCESSING));
        StatementRecord pushed = require(id, companyId);
        audit(pushed, "PUSH_VOUCHER", "SUCCESS", previousPushStatus, pushed.getPushStatus(), operatorId,
                pushed.getVoucherNo());
        return toResponse(pushed);
    }

    public PageResponse<StatementImportBatchResponse> pageBatches(int page, int size, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        Page<StatementImportBatch> result = batchMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<StatementImportBatch>().eq(StatementImportBatch::getCompanyId, companyId)
                        .orderByDesc(StatementImportBatch::getCreatedAt)
                        .orderByDesc(StatementImportBatch::getId));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toBatchResponse).toList());
    }

    public StatementImportBatchResponse getBatch(Long id, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        StatementImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<StatementImportBatch>()
                .eq(StatementImportBatch::getId, id)
                .eq(StatementImportBatch::getCompanyId, companyId));
        if (batch == null) {
            throw new BusinessException(404, "Import batch not found");
        }
        return toBatchResponse(batch);
    }

    public StatementDashboardResponse dashboard(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<StatementRecord> records = list(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, companyId));
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

    private StatementRecord require(Long id, long companyId) {
        StatementRecord statement = getOne(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getId, id)
                .eq(StatementRecord::getCompanyId, companyId));
        if (statement == null) throw new BusinessException(404, "Statement not found");
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
