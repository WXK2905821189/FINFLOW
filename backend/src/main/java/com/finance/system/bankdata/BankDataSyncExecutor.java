package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BankDataSyncExecutor {

    private static final String VALID = "VALID";
    private static final String INVALID = "INVALID";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_WINDOW = 100;

    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataSyncEvidenceService evidenceService;
    private final BankDataStatementMapper statementMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, BankDataAdapter> adapters;

    public BankDataSyncExecutor(BankDataSyncTaskMapper taskMapper,
                                BankDataBalanceMapper balanceMapper,
                                BankDataSyncEvidenceService evidenceService,
                                BankDataStatementMapper statementMapper,
                                BankDataSyncLogMapper logMapper,
                                BankAccountMapper bankAccountMapper,
                                ObjectMapper objectMapper,
                                List<BankDataAdapter> adapterList) {
        this.taskMapper = taskMapper;
        this.balanceMapper = balanceMapper;
        this.evidenceService = evidenceService;
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

        List<WindowRange> windows = splitWindows(task.getWindowStart(), task.getWindowEnd());
        List<CollectedStatement> collectedStatements = new ArrayList<>();
        List<CollectedBalance> collectedBalances = new ArrayList<>();
        int rawCount = 0;
        String lastBankRequestNo = null;
        for (WindowRange window : windows) {
            String cursor = null;
            int page = 1;
            while (page <= MAX_PAGES_PER_WINDOW) {
                BankDataSyncContext context = new BankDataSyncContext(companyId, task.getConnectionId(),
                        task.getBankAccountId(), task.getTaskNo(), task.getRequestId(), window.start(), window.end(),
                        page, cursor, PAGE_SIZE, "STATEMENT");
                BankDataCollection collection = Objects.requireNonNull(adapter.collect(context), "Adapter returned no collection");
                String status = normalizeStatus(collection.status() == null ? collection.bankStatusCode() : collection.status());
                String rawPayload = serialize(collection);
                BankDataRawMessage raw = evidenceService.persistRaw(task, collection.bankRequestNo(), rawPayload,
                        sha256(rawPayload), LocalDateTime.now());
                lastBankRequestNo = collection.bankRequestNo();
                log(task, "INFO", "BANK_PAGE_COLLECTED", status, collection.bankRequestNo(),
                        "Collected window " + window.start() + " to " + window.end() + ", page " + page);
                if (!"SUCCESS".equals(status)) {
                    task.setBankRequestNo(lastBankRequestNo);
                    task.setStatus(status);
                    task.setRawCount(rawCount);
                    task.setNormalizedCount(0);
                    task.setDuplicateCount(0);
                    task.setInvalidCount(0);
                    task.setErrorMessage("PENDING".equals(status) ? "Bank response is pending reconciliation"
                            : "UNKNOWN".equals(status) ? "Bank response status is unknown and requires manual reconciliation"
                            : "Bank response failed safely before normalization");
                    task.setCompletedAt(LocalDateTime.now());
                    taskMapper.updateById(task);
                    return task;
                }

                List<BankDataEntry> pageEntries = collection.entries() == null ? List.of() : collection.entries();
                List<BankDataBalanceEntry> pageBalances = collection.balances() == null ? List.of() : collection.balances();
                rawCount += pageEntries.size() + pageBalances.size();
                pageEntries.forEach(entry -> collectedStatements.add(new CollectedStatement(entry, raw, collection.bankRequestNo())));
                pageBalances.forEach(balance -> collectedBalances.add(new CollectedBalance(balance, raw, collection.bankRequestNo())));
                // An empty page is terminal even when a vendor response incorrectly leaves hasMore=true.
                // This prevents an adapter defect from causing repeated requests until the safety cap.
                if (pageEntries.isEmpty() && pageBalances.isEmpty()) {
                    break;
                }
                if (!collection.hasMore()) {
                    break;
                }
                String nextCursor = collection.nextCursor();
                if (nextCursor == null || nextCursor.isBlank() || Objects.equals(nextCursor, cursor)) {
                    throw new BusinessException(409, "Bank data adapter returned an unsafe pagination cursor");
                }
                cursor = nextCursor;
                page++;
            }
            if (page > MAX_PAGES_PER_WINDOW) {
                throw new BusinessException(409, "Bank data adapter pagination did not terminate safely");
            }
        }

        collectedStatements.sort(Comparator
                .comparing((CollectedStatement item) -> item.entry().transactionTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.entry().statementNo(), Comparator.nullsLast(String::compareTo)));
        collectedBalances.sort(Comparator
                .comparing((CollectedBalance item) -> item.entry().asOfTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.entry().bankRequestNo(), Comparator.nullsLast(String::compareTo)));

        int normalized = 0;
        int duplicates = 0;
        int invalid = 0;
        Set<String> statementKeys = new HashSet<>();
        for (CollectedStatement collected : collectedStatements) {
            BankDataEntry entry = collected.entry();
            String validationMessage = validate(entry, companyId, task.getBankAccountId());
            if (!validationMessage.isBlank()) {
                invalid++;
                log(task, "WARN", "STATEMENT_VALIDATION", "INVALID", collected.bankRequestNo(), validationMessage);
                continue;
            }
            BankDataStatement statement = toStatement(entry, task, collected.raw(), collected.bankRequestNo());
            String statementKey = statementKey(statement);
            if (!statementKeys.add(statementKey)) {
                duplicates++;
                log(task, "INFO", "STATEMENT_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                        "Duplicate statement found across windows or pages");
                continue;
            }
            try {
                if (statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                        .eq(BankDataStatement::getCompanyId, companyId)
                        .eq(BankDataStatement::getBankAccountId, statement.getBankAccountId())
                        .eq(BankDataStatement::getStatementNo, statement.getStatementNo())
                        .eq(BankDataStatement::getTransactionTime, statement.getTransactionTime())
                        .eq(BankDataStatement::getAmount, statement.getAmount())) > 0) {
                    duplicates++;
                    log(task, "INFO", "STATEMENT_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                            "Composite bank statement key already exists");
                    continue;
                }
                statementMapper.insert(statement);
                normalized++;
            } catch (DuplicateKeyException duplicateKeyException) {
                duplicates++;
                log(task, "INFO", "STATEMENT_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                        "Composite bank statement key already exists");
            }
        }

        Set<String> balanceKeys = new HashSet<>();
        for (CollectedBalance collected : collectedBalances) {
            BankDataBalanceEntry balance = collected.entry();
            String validationMessage = validateBalance(balance, companyId, task.getBankAccountId());
            if (!validationMessage.isBlank()) {
                invalid++;
                log(task, "WARN", "BALANCE_VALIDATION", "INVALID", collected.bankRequestNo(), validationMessage);
                continue;
            }
            BankDataBalance snapshot = toBalance(balance, task, collected.raw(), collected.bankRequestNo());
            String balanceKey = balanceKey(snapshot);
            if (!balanceKeys.add(balanceKey)) {
                duplicates++;
                log(task, "INFO", "BALANCE_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                        "Duplicate balance found across windows or pages");
                continue;
            }
            try {
                if (balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                        .eq(BankDataBalance::getCompanyId, companyId)
                        .eq(BankDataBalance::getBankAccountId, snapshot.getBankAccountId())
                        .eq(BankDataBalance::getAsOfTime, snapshot.getAsOfTime())) > 0) {
                    duplicates++;
                    log(task, "INFO", "BALANCE_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                            "Bank balance snapshot key already exists");
                    continue;
                }
                balanceMapper.insert(snapshot);
                normalized++;
            } catch (DuplicateKeyException duplicateKeyException) {
                duplicates++;
                log(task, "INFO", "BALANCE_DEDUPLICATED", "DUPLICATE", collected.bankRequestNo(),
                        "Bank balance snapshot key already exists");
            }
        }

        task.setBankRequestNo(lastBankRequestNo);
        task.setStatus(invalid == 0 ? "SUCCEEDED" : "PARTIAL");
        task.setRawCount(rawCount);
        task.setNormalizedCount(normalized);
        task.setDuplicateCount(duplicates);
        task.setInvalidCount(invalid);
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log(task, "INFO", "SYNC_COMPLETED", task.getStatus(), lastBankRequestNo,
                "Bank data synchronization completed without external network calls");
        return task;
    }

    private List<WindowRange> splitWindows(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        LocalDateTime start = requestedStart == null ? LocalDateTime.of(2026, 8, 27, 0, 0) : requestedStart;
        LocalDateTime end = requestedEnd == null ? start.plusDays(1) : requestedEnd;
        if (!end.isAfter(start)) {
            throw new BusinessException(400, "Bank data sync window end must be after start");
        }
        List<WindowRange> windows = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(end)) {
            LocalDate nextDate = cursor.toLocalDate().plusDays(1);
            LocalDateTime next = LocalDateTime.of(nextDate, LocalTime.MIDNIGHT);
            if (next.isAfter(end)) next = end;
            windows.add(new WindowRange(cursor, next));
            cursor = next;
        }
        return windows;
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

    private String validateBalance(BankDataBalanceEntry entry, long companyId, Long expectedAccountId) {
        if (entry == null) return "balance entry is required";
        if (entry.asOfTime() == null) return "balance asOfTime is required";
        if (entry.bankAccountId() == null || !entry.bankAccountId().equals(expectedAccountId)) {
            return "balance bankAccountId is outside the sync scope";
        }
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, entry.bankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) return "balance bankAccountId is not in the current company";
        if (entry.availableBalance() == null) return "availableBalance is required";
        if (entry.availableBalance().scale() > 2) return "availableBalance must have at most two decimal places";
        if (entry.currency() != null && !"CNY".equalsIgnoreCase(entry.currency())) return "balance currency must be CNY";
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

    private BankDataBalance toBalance(BankDataBalanceEntry entry, BankDataSyncTask task, BankDataRawMessage raw,
                                      String collectionBankRequestNo) {
        BankDataBalance balance = new BankDataBalance();
        balance.setCompanyId(task.getCompanyId());
        balance.setTaskId(task.getId());
        balance.setRawMessageId(raw.getId());
        balance.setBankAccountId(entry.bankAccountId());
        balance.setBankRequestNo(entry.bankRequestNo() == null ? collectionBankRequestNo : entry.bankRequestNo());
        balance.setAvailableBalance(entry.availableBalance().setScale(2));
        balance.setCurrency(entry.currency() == null || entry.currency().isBlank()
                ? "CNY" : entry.currency().trim().toUpperCase(Locale.ROOT));
        balance.setAsOfTime(entry.asOfTime());
        balance.setValidationStatus(VALID);
        return balance;
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

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "AAAAAAA" -> "SUCCESS";
            case "PENDING", "PROCESSING", "AAAAAAE" -> "PENDING";
            case "FAILED", "EEEEEEE" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private String statementKey(BankDataStatement statement) {
        return statement.getBankAccountId() + "|" + statement.getStatementNo() + "|"
                + statement.getTransactionTime() + "|" + statement.getAmount();
    }

    private String balanceKey(BankDataBalance balance) {
        return balance.getBankAccountId() + "|" + balance.getAsOfTime();
    }

    private record WindowRange(LocalDateTime start, LocalDateTime end) {}

    private record CollectedStatement(BankDataEntry entry, BankDataRawMessage raw, String bankRequestNo) {}

    private record CollectedBalance(BankDataBalanceEntry entry, BankDataRawMessage raw, String bankRequestNo) {}
}
