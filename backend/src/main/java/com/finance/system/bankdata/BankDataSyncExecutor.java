package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class BankDataSyncExecutor {

    private static final String VALID = "VALID";
    private static final String INVALID = "INVALID";

    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataStatementMapper statementMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, BankDataAdapter> adapters;

    public BankDataSyncExecutor(BankDataSyncTaskMapper taskMapper,
                                BankDataRawMessageMapper rawMessageMapper,
                                BankDataStatementMapper statementMapper,
                                BankDataSyncLogMapper logMapper,
                                BankAccountMapper bankAccountMapper,
                                ObjectMapper objectMapper,
                                List<BankDataAdapter> adapterList) {
        this.taskMapper = taskMapper;
        this.rawMessageMapper = rawMessageMapper;
        this.statementMapper = statementMapper;
        this.logMapper = logMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.objectMapper = objectMapper;
        this.adapters = adapterList.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> adapter.adapterCode().toUpperCase(Locale.ROOT), adapter -> adapter));
    }

    @Transactional
    public BankDataSyncTask execute(Long taskId, long companyId) {
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, taskId)
                .eq(BankDataSyncTask::getCompanyId, companyId));
        if (task == null) {
            throw new BusinessException(404, "Bank data sync task not found");
        }
        BankDataAdapter adapter = adapters.get(task.getAdapterCode().toUpperCase(Locale.ROOT));
        if (adapter == null) {
            throw new BusinessException(400, "Bank data adapter is not available");
        }

        BankDataSyncContext context = new BankDataSyncContext(companyId, task.getConnectionId(),
                task.getBankAccountId(), task.getTaskNo(), task.getRequestId());
        BankDataCollection collection = Objects.requireNonNull(adapter.collect(context), "Adapter returned no collection");
        List<BankDataEntry> entries = collection.entries() == null ? List.of() : collection.entries();
        String rawPayload = serialize(collection);
        LocalDateTime receivedAt = LocalDateTime.now();
        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode(task.getAdapterCode());
        raw.setBankRequestNo(collection.bankRequestNo());
        raw.setContentSha256(sha256(rawPayload));
        raw.setPayload(rawPayload);
        raw.setReceivedAt(receivedAt);
        raw.setRetentionUntil(receivedAt.plusDays(30));
        rawMessageMapper.insert(raw);

        int normalized = 0;
        int duplicates = 0;
        int invalid = 0;
        for (BankDataEntry entry : entries) {
            String validationMessage = validate(entry, companyId, task.getBankAccountId());
            if (!validationMessage.isBlank()) {
                invalid++;
                log(task, "WARN", "STATEMENT_VALIDATION", "INVALID", collection.bankRequestNo(), validationMessage);
                continue;
            }
            BankDataStatement statement = toStatement(entry, task, raw, collection.bankRequestNo());
            try {
                if (statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                        .eq(BankDataStatement::getCompanyId, companyId)
                        .eq(BankDataStatement::getBankAccountId, statement.getBankAccountId())
                        .eq(BankDataStatement::getStatementNo, statement.getStatementNo())
                        .eq(BankDataStatement::getTransactionTime, statement.getTransactionTime())
                        .eq(BankDataStatement::getAmount, statement.getAmount())) > 0) {
                    duplicates++;
                    log(task, "INFO", "STATEMENT_DEDUPLICATED", "DUPLICATE", collection.bankRequestNo(),
                            "Composite bank statement key already exists");
                    continue;
                }
                statementMapper.insert(statement);
                normalized++;
            } catch (DuplicateKeyException duplicateKeyException) {
                duplicates++;
                log(task, "INFO", "STATEMENT_DEDUPLICATED", "DUPLICATE", collection.bankRequestNo(),
                        "Composite bank statement key already exists");
            }
        }

        task.setBankRequestNo(collection.bankRequestNo());
        task.setStatus(invalid == 0 ? "SUCCEEDED" : "PARTIAL");
        task.setRawCount(entries.size());
        task.setNormalizedCount(normalized);
        task.setDuplicateCount(duplicates);
        task.setInvalidCount(invalid);
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log(task, "INFO", "SYNC_COMPLETED", task.getStatus(), collection.bankRequestNo(),
                "Bank data synchronization completed without external network calls");
        return task;
    }

    private String validate(BankDataEntry entry, long companyId, Long expectedAccountId) {
        if (entry == null) return "entry is required";
        if (entry.statementNo() == null || entry.statementNo().isBlank()) return "statementNo is required";
        if (entry.transactionTime() == null) return "transactionTime is required";
        if (entry.bankAccountId() == null || !entry.bankAccountId().equals(expectedAccountId)) return "bankAccountId is outside the sync scope";
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, entry.bankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) return "bankAccountId is not in the current company";
        if (entry.direction() == null || !("INCOME".equalsIgnoreCase(entry.direction()) || "EXPENSE".equalsIgnoreCase(entry.direction()))) {
            return "direction must be INCOME or EXPENSE";
        }
        if (entry.amount() == null || entry.amount().compareTo(BigDecimal.ZERO) <= 0) return "amount must be greater than zero";
        if (entry.amount().scale() > 2) return "amount must have at most two decimal places";
        if (entry.currency() != null && !"CNY".equalsIgnoreCase(entry.currency())) return "currency must be CNY";
        if (entry.statementNo().trim().length() > 128) return "statementNo is too long";
        return "";
    }

    private BankDataStatement toStatement(BankDataEntry entry, BankDataSyncTask task, BankDataRawMessage raw,
                                           String collectionBankRequestNo) {
        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(task.getCompanyId());
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(entry.bankAccountId());
        statement.setBankRequestNo(entry.bankRequestNo() == null ? collectionBankRequestNo : entry.bankRequestNo());
        statement.setStatementNo(entry.statementNo().trim());
        statement.setTransactionTime(entry.transactionTime());
        statement.setDirection(entry.direction().trim().toUpperCase(Locale.ROOT));
        statement.setAmount(entry.amount().setScale(2));
        statement.setCurrency(entry.currency() == null || entry.currency().isBlank() ? "CNY" : entry.currency().trim().toUpperCase(Locale.ROOT));
        statement.setCounterpartyName(trimToNull(entry.counterpartyName()));
        statement.setCounterpartyAccountMasked(maskAccount(entry.counterpartyAccount()));
        statement.setSummary(trimToNull(entry.summary()));
        statement.setValidationStatus(VALID);
        return statement;
    }

    private void log(BankDataSyncTask task, String level, String eventType, String result,
                     String bankRequestNo, String message) {
        BankDataSyncLog log = new BankDataSyncLog();
        log.setCompanyId(task.getCompanyId());
        log.setTaskId(task.getId());
        log.setLevel(level);
        log.setEventType(eventType);
        log.setResult(result);
        log.setRequestId(task.getRequestId());
        log.setBankRequestNo(bankRequestNo);
        log.setMessage(message == null || message.length() <= 500 ? message : message.substring(0, 500));
        logMapper.insert(log);
    }

    private String serialize(BankDataCollection collection) {
        try {
            return objectMapper.writeValueAsString(collection);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "Bank data payload cannot be serialized");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte part : digest) result.append(String.format("%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String maskAccount(String value) {
        if (value == null || value.isBlank()) return null;
        String account = value.trim();
        return account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4);
    }
}
