package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.dto.BankDataReconciliationResponse;
import com.finance.system.bankdata.dto.BankDataStatementDetailResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncLogResponse;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.bankdata.dto.BankDataProjectionResponse;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataConnectionResponse;
import com.finance.system.bankdata.dto.BankSyncJobDetailResponse;
import com.finance.system.bankdata.dto.BankSyncJobEventResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.bankdata.dto.BankSyncJobTriggerRequest;
import com.finance.system.bankdata.scope.CompanyScopeService;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BankDataSyncService {

    private final CompanyScopeService companyScope;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataStatementMapper statementMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ConnectionProfileMapper connectionProfileMapper;
    private final BankDataSyncExecutor executor;
    private final BankDataSyncEvidenceService evidenceService;
    private final Map<String, BankDataAdapter> adapters;

    public BankDataSyncService(CompanyScopeService companyScope,
                               BankDataSyncTaskMapper taskMapper,
                               BankDataStatementMapper statementMapper,
                               BankDataBalanceMapper balanceMapper,
                               BankDataRawMessageMapper rawMessageMapper,
                               BankDataSyncLogMapper logMapper,
                               BankAccountMapper bankAccountMapper,
                               ConnectionProfileMapper connectionProfileMapper,
                               BankDataSyncExecutor executor,
                               BankDataSyncEvidenceService evidenceService,
                               List<BankDataAdapter> adapterList) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.statementMapper = statementMapper;
        this.balanceMapper = balanceMapper;
        this.rawMessageMapper = rawMessageMapper;
        this.logMapper = logMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.connectionProfileMapper = connectionProfileMapper;
        this.executor = executor;
        this.evidenceService = evidenceService;
        this.adapters = adapterList.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> adapter.adapterCode().toUpperCase(Locale.ROOT), adapter -> adapter));
    }

    public BankDataSyncTaskDetailResponse trigger(Long userId, BankDataSyncRequest request, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        return triggerForCompany(companyId, userId, request, requestId);
    }

    public BankSyncJobDetailResponse triggerJob(Long userId, BankSyncJobTriggerRequest request, String requestId) {
        if (!"STATEMENT_PULL".equalsIgnoreCase(request.jobType())) {
            throw new BusinessException(400, "Only STATEMENT_PULL is available for the simulated bank data adapter");
        }
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, companyId)
                .eq(BankAccount::getStatus, "ACTIVE")
                .orderByAsc(BankAccount::getId)
                .last("LIMIT 1"));
        if (account == null) {
            throw new BusinessException(404, "No active bank account is available in the current company");
        }
        String safeRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        BankDataSyncTaskDetailResponse detail = trigger(userId,
                new BankDataSyncRequest(request.connectionCode(), account.getId(), "MOCK"), safeRequestId);
        BankDataSyncTaskResponse task = detail.task();
        BankSyncJobResponse job = new BankSyncJobResponse(task.id(), task.taskNo(),
                normalize(request.jobType(), "STATEMENT_PULL"), "MANUAL", task.connectionCode(), task.status(),
                task.requestId(), "raw=" + task.rawCount() + ", normalized=" + task.normalizedCount()
                        + ", duplicates=" + task.duplicateCount() + ", invalid=" + task.invalidCount(),
                task.startedAt(), task.completedAt(), task.createdAt());
        List<BankSyncJobEventResponse> timeline = detail.logs().stream()
                .map(log -> new BankSyncJobEventResponse(log.result(), log.eventType(), log.message(),
                        log.requestId(), log.createdAt()))
                .toList();
        return new BankSyncJobDetailResponse(job, timeline);
    }

    public PageResponse<BankSyncJobResponse> listJobs(Long userId, int page, int size, String status, String jobType) {
        if (jobType != null && !jobType.isBlank() && !"STATEMENT_PULL".equalsIgnoreCase(jobType)) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        PageResponse<BankDataSyncTaskResponse> tasks = listTasks(userId, page, size, status, null);
        List<BankSyncJobResponse> records = tasks.records().stream()
                .map(task -> toJobResponse(task, "STATEMENT_PULL", "MANUAL"))
                .toList();
        return new PageResponse<>(tasks.page(), tasks.size(), tasks.total(), records);
    }

    public BankSyncJobDetailResponse getJob(Long userId, Long taskId) {
        BankDataSyncTaskDetailResponse detail = getTaskDetail(userId, taskId);
        BankDataSyncTaskResponse task = detail.task();
        BankSyncJobResponse job = toJobResponse(task, "STATEMENT_PULL", "MANUAL");
        List<BankSyncJobEventResponse> timeline = detail.logs().stream()
                .map(log -> new BankSyncJobEventResponse(log.result(), log.eventType(), log.message(),
                        log.requestId(), log.createdAt()))
                .toList();
        return new BankSyncJobDetailResponse(job, timeline);
    }

    public PageResponse<BankDataProjectionResponse> queryProjection(Long userId, String resource,
                                                                      int page, int size, String status,
                                                                      Long bankAccountId, String keyword,
                                                                      LocalDateTime from, LocalDateTime to) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("balances", "statements", "receipts", "reconciliations", "payments", "payroll")
                .contains(normalized)) {
            throw new BusinessException(404, "Bank data projection not found");
        }
        if ("balances".equals(normalized)) {
            PageResponse<BankDataBalanceResponse> balances = listBalances(userId, page, size, bankAccountId,
                    status, from, to);
            List<BankDataProjectionResponse> records = balances.records().stream()
                    .map(balance -> new BankDataProjectionResponse(String.valueOf(balance.id()), "BANKDATA",
                            "BALANCE-" + balance.id(), balance.validationStatus(), balance.asOfTime(),
                            balance.accountMasked(), balance.availableBalance(), balance.currency(), null,
                            "Available balance snapshot"))
                    .toList();
            return new PageResponse<>(balances.page(), balances.size(), balances.total(), records);
        }
        if (!"statements".equals(normalized)) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .eq(status != null && !status.isBlank(), BankDataStatement::getValidationStatus,
                        status == null ? null : status.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .and(keyword != null && !keyword.isBlank(), nested -> nested
                        .like(BankDataStatement::getStatementNo, keyword.trim())
                        .or().like(BankDataStatement::getSummary, keyword.trim()))
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId);
        Page<BankDataStatement> result = statementMapper.selectPage(
                new Page<>(Math.max(1, page), boundedSize(size)), query);
        List<BankDataProjectionResponse> records = result.getRecords().stream()
                .map(statement -> new BankDataProjectionResponse(
                        String.valueOf(statement.getId()), "BANKDATA", statement.getStatementNo(),
                        statement.getValidationStatus(), statement.getTransactionTime(), null, statement.getAmount(),
                        statement.getCurrency(), statement.getDirection(), statement.getSummary()))
                .toList();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    public PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                               Long bankAccountId, LocalDateTime from, LocalDateTime to) {
        return listBalances(userId, page, size, bankAccountId, null, from, to);
    }

    private PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                                Long bankAccountId, String validationStatus,
                                                                LocalDateTime from, LocalDateTime to) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<BankDataBalance> query = new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataBalance::getBankAccountId, bankAccountId)
                .eq(validationStatus != null && !validationStatus.isBlank(), BankDataBalance::getValidationStatus,
                        validationStatus == null ? null : validationStatus.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataBalance::getAsOfTime, from)
                .le(to != null, BankDataBalance::getAsOfTime, to)
                .orderByDesc(BankDataBalance::getAsOfTime)
                .orderByDesc(BankDataBalance::getId);
        Page<BankDataBalance> result = balanceMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(balance -> toBalanceResponse(balance, companyId)).toList());
    }

    public List<BankDataConnectionResponse> listConnections(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        return connectionProfileMapper.selectList(new LambdaQueryWrapper<ConnectionProfile>()
                        .eq(ConnectionProfile::getCompanyId, companyId)
                        .orderByAsc(ConnectionProfile::getConnectionCode)
                        .orderByAsc(ConnectionProfile::getId))
                .stream().map(profile -> new BankDataConnectionResponse(profile.getConnectionCode(),
                        profile.getDisplayName(), profile.getProviderType(), Boolean.TRUE.equals(profile.getEnabled()),
                        profile.getStatus(), profile.getLastCheckedAt(),
                        "No credential, private key, token, or certificate content is stored or returned"))
                .toList();
    }

    public BankDataSyncTaskDetailResponse triggerForCompany(long companyId, Long requestedBy,
                                                            BankDataSyncRequest request, String requestId) {
        String adapterCode = normalize(request.adapterCode(), "MOCK");
        if (!adapters.containsKey(adapterCode)) {
            throw new BusinessException(400, "Bank data adapter is not available");
        }
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, request.bankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) {
            throw new BusinessException(404, "Bank account not found in the current company");
        }
        Long connectionId = null;
        if (request.connectionCode() != null && !request.connectionCode().isBlank()) {
            ConnectionProfile profile = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                    .eq(ConnectionProfile::getCompanyId, companyId)
                    .eq(ConnectionProfile::getConnectionCode, request.connectionCode().trim()));
            if (profile == null) throw new BusinessException(404, "Connection not found in the current company");
            connectionId = profile.getId();
        }
        String safeRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        if (safeRequestId.length() > 64) safeRequestId = safeRequestId.substring(0, 64);

        BankDataSyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(BankDataSyncTask::getRequestId, safeRequestId));
        if (existing != null) {
            if (!existing.getBankAccountId().equals(account.getId())
                    || !existing.getAdapterCode().equals(adapterCode)
                    || !java.util.Objects.equals(existing.getConnectionId(), connectionId)) {
                throw new BusinessException(409, "Request id was already used for a different synchronization");
            }
            return getTaskDetail(existing.getId(), companyId);
        }

        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        task.setAdapterCode(adapterCode);
        task.setConnectionId(connectionId);
        task.setBankAccountId(account.getId());
        task.setRequestedBy(requestedBy);
        task.setRequestId(safeRequestId);
        task.setStatus("RUNNING");
        task.setRawCount(0);
        task.setNormalizedCount(0);
        task.setDuplicateCount(0);
        task.setInvalidCount(0);
        task.setStartedAt(LocalDateTime.now());
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicateKeyException) {
            BankDataSyncTask concurrent = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                    .eq(BankDataSyncTask::getCompanyId, companyId)
                    .eq(BankDataSyncTask::getRequestId, safeRequestId));
            if (concurrent == null) throw duplicateKeyException;
            if (!concurrent.getBankAccountId().equals(account.getId())
                    || !concurrent.getAdapterCode().equals(adapterCode)
                    || !java.util.Objects.equals(concurrent.getConnectionId(), connectionId)) {
                throw new BusinessException(409, "Request id was already used for a different synchronization");
            }
            return getTaskDetail(concurrent.getId(), companyId);
        }

        try {
            executor.execute(task.getId(), companyId);
        } catch (RuntimeException exception) {
            task.setStatus("FAILED");
            task.setErrorMessage(safeMessage(exception));
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            evidenceService.recordFailure(task, task.getErrorMessage());
        }
        return getTaskDetail(task.getId(), companyId);
    }

    public PageResponse<BankDataSyncTaskResponse> listTasks(Long userId, int page, int size,
                                                              String status, String adapterCode) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<BankDataSyncTask> query = new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(status != null && !status.isBlank(), BankDataSyncTask::getStatus, normalize(status, null))
                .eq(adapterCode != null && !adapterCode.isBlank(), BankDataSyncTask::getAdapterCode, normalize(adapterCode, null))
                .orderByDesc(BankDataSyncTask::getCreatedAt)
                .orderByDesc(BankDataSyncTask::getId);
        Page<BankDataSyncTask> result = taskMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(task -> toTaskResponse(task, connectionCode(companyId, task.getConnectionId()))).toList());
    }

    public BankDataSyncTaskDetailResponse getTaskDetail(Long userId, Long taskId) {
        return getTaskDetail(taskId, companyScope.companyIdForUser(userId));
    }

    private BankDataSyncTaskDetailResponse getTaskDetail(Long taskId, long companyId) {
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, taskId)
                .eq(BankDataSyncTask::getCompanyId, companyId));
        if (task == null) throw new BusinessException(404, "Bank data sync task not found");
        List<BankDataSyncLogResponse> logs = logMapper.selectList(new LambdaQueryWrapper<BankDataSyncLog>()
                        .eq(BankDataSyncLog::getCompanyId, companyId)
                        .eq(BankDataSyncLog::getTaskId, taskId)
                        .orderByAsc(BankDataSyncLog::getCreatedAt)
                        .orderByAsc(BankDataSyncLog::getId))
                .stream().map(this::toLogResponse).toList();
        return new BankDataSyncTaskDetailResponse(toTaskResponse(task, connectionCode(companyId, task.getConnectionId())), logs);
    }

    public PageResponse<BankDataStatementResponse> listStatements(Long userId, int page, int size,
                                                                    Long bankAccountId, String direction,
                                                                    LocalDateTime from, LocalDateTime to) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .eq(direction != null && !direction.isBlank(), BankDataStatement::getDirection, normalize(direction, null))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId);
        Page<BankDataStatement> result = statementMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(statement -> toStatementResponse(statement, companyId)).toList());
    }

    public BankDataStatementDetailResponse getStatement(Long userId, Long statementId) {
        long companyId = companyScope.companyIdForUser(userId);
        BankDataStatement statement = statementMapper.selectOne(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getId, statementId)
                .eq(BankDataStatement::getCompanyId, companyId));
        if (statement == null) throw new BusinessException(404, "Bank data statement not found");
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, statement.getTaskId())
                .eq(BankDataSyncTask::getCompanyId, companyId));
        List<BankDataSyncLogResponse> logs = logMapper.selectList(new LambdaQueryWrapper<BankDataSyncLog>()
                        .eq(BankDataSyncLog::getCompanyId, companyId)
                        .eq(BankDataSyncLog::getTaskId, statement.getTaskId())
                        .orderByAsc(BankDataSyncLog::getCreatedAt)
                        .orderByAsc(BankDataSyncLog::getId))
                .stream().map(this::toLogResponse).toList();
        return new BankDataStatementDetailResponse(toStatementResponse(statement, companyId),
                toTaskResponse(task, task == null ? null : connectionCode(companyId, task.getConnectionId())), logs);
    }

    public BankDataReconciliationResponse reconciliation(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<BankDataStatement> statements = statementMapper.selectList(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId));
        List<BankDataSyncTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId));
        BigDecimal total = statements.stream().map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal income = statements.stream().filter(s -> "INCOME".equals(s.getDirection())).map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = statements.stream().filter(s -> "EXPENSE".equals(s.getDirection())).map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long duplicates = tasks.stream().mapToLong(task -> task.getDuplicateCount() == null ? 0 : task.getDuplicateCount()).sum();
        long invalid = tasks.stream().mapToLong(task -> task.getInvalidCount() == null ? 0 : task.getInvalidCount()).sum();
        return new BankDataReconciliationResponse(statements.size(), statements.size(), duplicates, invalid,
                total.setScale(2), income.setScale(2), expense.setScale(2), tasks.size(),
                tasks.stream().filter(task -> "FAILED".equals(task.getStatus())).count());
    }

    public void triggerScheduledSyncs() {
        List<ConnectionProfile> profiles = connectionProfileMapper.selectList(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getEnabled, true)
                .in(ConnectionProfile::getStatus, List.of("SIMULATED", "ACTIVE")));
        for (ConnectionProfile profile : profiles) {
            if (profile.getCompanyId() == null) continue;
            BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                    .eq(BankAccount::getCompanyId, profile.getCompanyId())
                    .eq(BankAccount::getStatus, "ACTIVE")
                    .orderByAsc(BankAccount::getId)
                    .last("LIMIT 1"));
            if (account == null) continue;
            try {
                triggerForCompany(profile.getCompanyId(), null,
                        new BankDataSyncRequest(profile.getConnectionCode(), account.getId(), "MOCK"),
                        "scheduled-" + UUID.randomUUID());
            } catch (RuntimeException ignored) {
                // Scheduled execution is best effort; a persisted task records failures.
            }
        }
    }

    private void insertLog(BankDataSyncTask task, String level, String eventType, String result,
                           String bankRequestNo, String message) {
        BankDataSyncLog log = new BankDataSyncLog();
        log.setCompanyId(task.getCompanyId());
        log.setTaskId(task.getId());
        log.setLevel(level);
        log.setEventType(eventType);
        log.setResult(result);
        log.setRequestId(task.getRequestId());
        log.setBankRequestNo(bankRequestNo);
        log.setMessage(message);
        logMapper.insert(log);
    }

    private String connectionCode(long companyId, Long connectionId) {
        if (connectionId == null) return null;
        ConnectionProfile profile = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getId, connectionId)
                .eq(ConnectionProfile::getCompanyId, companyId));
        return profile == null ? null : profile.getConnectionCode();
    }

    private BankDataSyncTaskResponse toTaskResponse(BankDataSyncTask task, String connectionCode) {
        if (task == null) return null;
        return new BankDataSyncTaskResponse(task.getId(), task.getTaskNo(), task.getAdapterCode(), connectionCode,
                task.getBankAccountId(), task.getRequestId(), task.getBankRequestNo(), task.getStatus(), task.getRawCount(),
                task.getNormalizedCount(), task.getDuplicateCount(), task.getInvalidCount(), task.getErrorMessage(),
                task.getStartedAt(), task.getCompletedAt(), task.getCreatedAt());
    }

    private BankSyncJobResponse toJobResponse(BankDataSyncTaskResponse task, String jobType, String triggerType) {
        return new BankSyncJobResponse(task.id(), task.taskNo(), jobType, triggerType, task.connectionCode(),
                task.status(), task.requestId(), "raw=" + task.rawCount() + ", normalized=" + task.normalizedCount()
                + ", duplicates=" + task.duplicateCount() + ", invalid=" + task.invalidCount(),
                task.startedAt(), task.completedAt(), task.createdAt());
    }

    private BankDataStatementResponse toStatementResponse(BankDataStatement statement, long companyId) {
        BankDataRawMessage raw = rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getId, statement.getRawMessageId())
                .eq(BankDataRawMessage::getCompanyId, companyId));
        return new BankDataStatementResponse(statement.getId(), statement.getTaskId(), statement.getRawMessageId(),
                raw == null ? null : raw.getContentSha256(), raw == null ? null : raw.getRetentionUntil(),
                statement.getBankAccountId(), statement.getBankRequestNo(), statement.getStatementNo(), statement.getTransactionTime(),
                statement.getDirection(), statement.getAmount(), statement.getCurrency(), statement.getCounterpartyName(),
                statement.getCounterpartyAccountMasked(), statement.getSummary(), statement.getValidationStatus(),
                statement.getValidationMessage(), statement.getCreatedAt());
    }

    private BankDataBalanceResponse toBalanceResponse(BankDataBalance balance, long companyId) {
        BankDataRawMessage raw = rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getId, balance.getRawMessageId())
                .eq(BankDataRawMessage::getCompanyId, companyId));
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, balance.getBankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        return new BankDataBalanceResponse(balance.getId(), balance.getTaskId(), balance.getRawMessageId(),
                raw == null ? null : raw.getContentSha256(), raw == null ? null : raw.getRetentionUntil(),
                balance.getBankAccountId(), account == null ? null : maskAccount(account.getAccountNumber()),
                balance.getBankRequestNo(), balance.getAvailableBalance(), balance.getCurrency(), balance.getAsOfTime(),
                balance.getValidationStatus(), balance.getValidationMessage(), balance.getCreatedAt());
    }

    private BankDataSyncLogResponse toLogResponse(BankDataSyncLog log) {
        return new BankDataSyncLogResponse(log.getId(), log.getLevel(), log.getEventType(), log.getResult(),
                log.getRequestId(), log.getBankRequestNo(), sanitize(log.getMessage()), log.getCreatedAt());
    }

    private String sanitize(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                .replaceAll("(?<!\\d)\\d{8,}(?!\\d)", "****");
    }

    private String maskAccount(String value) {
        if (value == null || value.isBlank()) return null;
        String account = value.trim();
        return account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4);
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private long boundedSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    private String safeMessage(RuntimeException exception) {
        if (!(exception instanceof BusinessException)) {
            return "Bank data synchronization failed during internal processing";
        }
        String message = sanitize(exception.getMessage());
        return message == null || message.isBlank()
                ? "Bank data synchronization failed"
                : message.substring(0, Math.min(500, message.length()));
    }
}
