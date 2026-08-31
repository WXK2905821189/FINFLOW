package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.aggregation.BankDataAggregationService;
import com.finance.system.bankdata.dto.BankDataReconciliationResponse;
import com.finance.system.bankdata.dto.BankDataStatementDetailResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncLogResponse;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.bankdata.dto.BankDataProjectionResponse;
import com.finance.system.bankdata.dto.BankDataProjectionPageResponse;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataConnectionResponse;
import com.finance.system.bankdata.dto.BankSyncJobDetailResponse;
import com.finance.system.bankdata.dto.BankSyncJobEventResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.bankdata.dto.BankSyncJobTriggerRequest;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
    private final BankDataSyncLogMapper logMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ConnectionProfileMapper connectionProfileMapper;
    private final BankDataSyncExecutor executor;
    private final BankDataSyncEvidenceService evidenceService;
    private final BankDataSyncResponseAssembler responseAssembler;
    private final BankDataScheduledSyncService scheduledSyncService;
    private final BankDataAggregationService aggregationService;

    private record SyncWindow(LocalDateTime start, LocalDateTime end) {}

    public BankDataSyncService(CompanyScopeService companyScope,
                               BankDataSyncTaskMapper taskMapper,
                               BankDataStatementMapper statementMapper,
                               BankDataBalanceMapper balanceMapper,
                               BankDataSyncLogMapper logMapper,
                               BankAccountMapper bankAccountMapper,
                               ConnectionProfileMapper connectionProfileMapper,
                               BankDataSyncExecutor executor,
                               BankDataSyncEvidenceService evidenceService,
                               BankDataSyncResponseAssembler responseAssembler,
                               @Lazy BankDataScheduledSyncService scheduledSyncService,
                               BankDataAggregationService aggregationService) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.statementMapper = statementMapper;
        this.balanceMapper = balanceMapper;
        this.logMapper = logMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.connectionProfileMapper = connectionProfileMapper;
        this.executor = executor;
        this.evidenceService = evidenceService;
        this.responseAssembler = responseAssembler;
        this.scheduledSyncService = scheduledSyncService;
        this.aggregationService = aggregationService;
    }

    public BankDataSyncTaskDetailResponse trigger(Long userId, BankDataSyncRequest request, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        return triggerForCompany(companyId, userId, request, requestId, "MANUAL");
    }

    public BankSyncJobDetailResponse triggerJob(Long userId, BankSyncJobTriggerRequest request, String requestId) {
        if (!"STATEMENT_PULL".equalsIgnoreCase(request.jobType())) {
            throw new BusinessException(400, "Only STATEMENT_PULL is available for the simulated bank data adapter");
        }
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, request.bankAccountId())
                .eq(BankAccount::getCompanyId, companyId)
                .eq(BankAccount::getStatus, "ACTIVE"));
        if (account == null) {
            throw new BusinessException(404, "Bank account not found in the current company");
        }
        String safeRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        SyncWindow window = parseWindow(request.windowStart(), request.windowEnd());
        BankDataSyncTaskDetailResponse detail = trigger(userId,
                new BankDataSyncRequest(request.connectionCode(), account.getId(), "MOCK", window.start(), window.end()), safeRequestId);
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

    public PageResponse<BankSyncJobResponse> listJobs(Long userId, int page, int size, String status, String jobType,
                                                      String connectionCode, String requestId) {
        if (jobType != null && !jobType.isBlank() && !"STATEMENT_PULL".equalsIgnoreCase(jobType)) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        PageResponse<BankDataSyncTaskResponse> tasks = listTasks(userId, page, size, status, null,
                connectionCode, requestId);
        List<BankSyncJobResponse> records = tasks.records().stream()
                .map(task -> responseAssembler.job(task, "STATEMENT_PULL", "MANUAL"))
                .toList();
        return new PageResponse<>(tasks.page(), tasks.size(), tasks.total(), records);
    }

    public BankSyncJobDetailResponse getJob(Long userId, Long taskId) {
        BankDataSyncTaskDetailResponse detail = getTaskDetail(userId, taskId);
        BankDataSyncTaskResponse task = detail.task();
        BankSyncJobResponse job = responseAssembler.job(task, "STATEMENT_PULL", "MANUAL");
        List<BankSyncJobEventResponse> timeline = detail.logs().stream()
                .map(log -> new BankSyncJobEventResponse(log.result(), log.eventType(), log.message(),
                        log.requestId(), log.createdAt()))
                .toList();
        return new BankSyncJobDetailResponse(job, timeline);
    }

    public BankDataProjectionPageResponse queryProjection(Long userId, String resource,
                                                          int page, int size, String status,
                                                          Long bankAccountId, String keyword,
                                                          LocalDateTime from, LocalDateTime to,
                                                          String sourceSystem, String syncJobNo,
                                                          String requestId) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("balances", "statements", "receipts", "reconciliations", "payroll")
                .contains(normalized)) {
            throw new BusinessException(404, "Bank data projection not found");
        }
        long companyId = companyScope.companyIdForUser(userId);
        String normalizedSource = sourceSystem == null || sourceSystem.isBlank()
                ? null : sourceSystem.trim().toUpperCase(Locale.ROOT);
        if (normalizedSource != null && !List.of("BANKDATA", "MOCK", "SIMULATED").contains(normalizedSource)) {
            return emptyProjectionPage(page, size, "No projection matches the requested source");
        }
        List<Long> taskIds = scopedTaskIds(companyId, syncJobNo, requestId);
        if ((syncJobNo != null && !syncJobNo.isBlank() || requestId != null && !requestId.isBlank())
                && taskIds.isEmpty()) {
            return emptyProjectionPage(page, size, "No projection matches the requested task or request");
        }
        if ("balances".equals(normalized)) {
            PageResponse<BankDataBalanceResponse> balances = listBalances(userId, page, size, bankAccountId,
                    status, from, to, taskIds.isEmpty() ? null : taskIds);
            Map<Long, BankDataSyncTask> tasksById = tasksById(companyId,
                    balances.records().stream().map(BankDataBalanceResponse::taskId).toList());
            List<BankDataProjectionResponse> records = balances.records().stream()
                    .map(balance -> new BankDataProjectionResponse(String.valueOf(balance.id()), "BANKDATA",
                            "BALANCE-" + balance.id(), balance.validationStatus(), balance.asOfTime(),
                            balance.accountMasked(), balance.availableBalance(), balance.currency(), null,
                            "Available balance snapshot", taskNo(tasksById.get(balance.taskId())),
                            requestId(tasksById.get(balance.taskId())), balance.createdAt(), true))
                    .toList();
            return projectionPage(balances.page(), balances.size(), balances.total(), records,
                    companyId, "BANKDATA", balances.records().stream().map(BankDataBalanceResponse::createdAt)
                            .max(LocalDateTime::compareTo).orElse(null));
        }
        if (!"statements".equals(normalized)) {
            return emptyProjectionPage(page, size, "This projection is not populated in the current MOCK release");
        }
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .in(!taskIds.isEmpty(), BankDataStatement::getTaskId, taskIds)
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
        Map<Long, BankDataSyncTask> tasksById = tasksById(companyId,
                result.getRecords().stream().map(BankDataStatement::getTaskId).toList());
        List<BankDataProjectionResponse> records = result.getRecords().stream()
                .map(statement -> new BankDataProjectionResponse(
                        String.valueOf(statement.getId()), "BANKDATA", statement.getStatementNo(),
                        statement.getValidationStatus(), statement.getTransactionTime(), null, statement.getAmount(),
                        statement.getCurrency(), statement.getDirection(), statement.getSummary(),
                        taskNo(tasksById.get(statement.getTaskId())), requestId(tasksById.get(statement.getTaskId())),
                        statement.getCreatedAt(), true))
                .toList();
        return projectionPage(result.getCurrent(), result.getSize(), result.getTotal(), records,
                companyId, "BANKDATA", result.getRecords().stream().map(BankDataStatement::getCreatedAt)
                        .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
    }

    public PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                               Long bankAccountId, LocalDateTime from, LocalDateTime to) {
        return listBalances(userId, page, size, bankAccountId, from, to, null, null);
    }

    public PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                               Long bankAccountId, LocalDateTime from, LocalDateTime to,
                                                               String taskNo, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<Long> taskIds = scopedTaskIds(companyId, taskNo, requestId);
        if ((taskNo != null && !taskNo.isBlank() || requestId != null && !requestId.isBlank())
                && taskIds.isEmpty()) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        return listBalances(userId, page, size, bankAccountId, null, from, to, taskIds);
    }

    private PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                                Long bankAccountId, String validationStatus,
                                                                LocalDateTime from, LocalDateTime to,
                                                                List<Long> taskIds) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<BankDataBalance> query = new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataBalance::getBankAccountId, bankAccountId)
                .in(taskIds != null && !taskIds.isEmpty(), BankDataBalance::getTaskId, taskIds)
                .eq(validationStatus != null && !validationStatus.isBlank(), BankDataBalance::getValidationStatus,
                        validationStatus == null ? null : validationStatus.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataBalance::getAsOfTime, from)
                .le(to != null, BankDataBalance::getAsOfTime, to)
                .orderByDesc(BankDataBalance::getAsOfTime)
                .orderByDesc(BankDataBalance::getId);
        Page<BankDataBalance> result = balanceMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.balances(result.getRecords(), companyId));
    }

    private List<Long> scopedTaskIds(long companyId, String syncJobNo, String requestId) {
        if ((syncJobNo == null || syncJobNo.isBlank()) && (requestId == null || requestId.isBlank())) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .eq(syncJobNo != null && !syncJobNo.isBlank(), BankDataSyncTask::getTaskNo, syncJobNo == null ? null : syncJobNo.trim())
                        .eq(requestId != null && !requestId.isBlank(), BankDataSyncTask::getRequestId, requestId == null ? null : requestId.trim())
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    private Map<Long, BankDataSyncTask> tasksById(long companyId, List<Long> taskIds) {
        List<Long> ids = taskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .in(BankDataSyncTask::getId, ids)
                        .select(BankDataSyncTask::getId, BankDataSyncTask::getTaskNo, BankDataSyncTask::getRequestId))
                .stream().collect(Collectors.toMap(BankDataSyncTask::getId, java.util.function.Function.identity()));
    }

    private String taskNo(BankDataSyncTask task) {
        return task == null ? null : task.getTaskNo();
    }

    private String requestId(BankDataSyncTask task) {
        return task == null ? null : task.getRequestId();
    }

    private BankDataProjectionPageResponse projectionPage(long page, long size, long total,
                                                           List<BankDataProjectionResponse> records,
                                                           long companyId, String sourceSystem,
                                                           LocalDateTime lastSyncedAt) {
        return new BankDataProjectionPageResponse(page, size, total, records, false, "SIMULATED",
                "真实银行直联未启用；当前仅返回服务端模拟业务投影", null, sourceSystem, lastSyncedAt, true);
    }

    private BankDataProjectionPageResponse emptyProjectionPage(int page, int size, String message) {
        return new BankDataProjectionPageResponse(Math.max(1, page), boundedSize(size), 0, List.of(),
                false, "NOT_ENABLED", message, null, "BANKDATA", null, true);
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
        return triggerForCompany(companyId, requestedBy, request, requestId, "MANUAL");
    }

    /** @deprecated Scheduled scans are coordinated by BankDataScheduledSyncService. */
    @Deprecated(since = "0.2", forRemoval = false)
    public void triggerScheduledSyncs() {
        scheduledSyncService.triggerScheduledSyncs();
    }

    public BankDataSyncTaskDetailResponse triggerForCompany(long companyId, Long requestedBy,
                                                            BankDataSyncRequest request, String requestId,
                                                            String triggerType) {
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, request.bankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) {
            throw new BusinessException(404, "Bank account not found in the current company");
        }
        Long connectionId = null;
        ConnectionProfile connection = null;
        if (request.connectionCode() != null && !request.connectionCode().isBlank()) {
            connection = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                    .eq(ConnectionProfile::getCompanyId, companyId)
                    .eq(ConnectionProfile::getConnectionCode, request.connectionCode().trim()));
            if (connection == null) throw new BusinessException(404, "Connection not found in the current company");
            connectionId = connection.getId();
        }
        String adapterCode = aggregationService.resolveAdapterCode(request.adapterCode(),
                connection == null ? null : connection.getProviderType());
        String requestedRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        String safeRequestId = requestedRequestId.length() > 64 ? requestedRequestId.substring(0, 64) : requestedRequestId;
        SyncWindow window = parseWindow(request.windowStart(), request.windowEnd());
        String safeTriggerType = normalize(triggerType, "MANUAL");
        String syncKey = syncKey(account.getId(), connectionId, adapterCode, window);

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

        BankDataSyncTask running = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(BankDataSyncTask::getSyncKey, syncKey)
                .last("LIMIT 1"));
        if (running != null) {
            if ("RUNNING".equals(running.getStatus())) {
                throw new BusinessException(409, "A bank data sync task is already running for the same account and window");
            }
            recordTaskReused(running, safeRequestId);
            return getTaskDetail(running.getId(), companyId);
        }

        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        task.setAdapterCode(adapterCode);
        task.setMappingVersion(aggregationService.mappingVersion(adapterCode));
        task.setConnectionId(connectionId);
        task.setBankAccountId(account.getId());
        task.setRequestedBy(requestedBy);
        task.setRequestId(safeRequestId);
        task.setSyncKey(syncKey);
        task.setTriggerType(safeTriggerType);
        task.setWindowStart(window.start());
        task.setWindowEnd(window.end());
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
                    .and(wrapper -> wrapper.eq(BankDataSyncTask::getRequestId, safeRequestId)
                            .or().eq(BankDataSyncTask::getSyncKey, syncKey))
                    .last("LIMIT 1"));
            if (concurrent == null) throw duplicateKeyException;
            if (!concurrent.getBankAccountId().equals(account.getId())
                    || !concurrent.getAdapterCode().equals(adapterCode)
                    || !java.util.Objects.equals(concurrent.getConnectionId(), connectionId)) {
                throw new BusinessException(409, "Request id was already used for a different synchronization");
            }
            recordTaskReused(concurrent, safeRequestId);
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
        return listTasks(userId, page, size, status, adapterCode, null, null);
    }

    private PageResponse<BankDataSyncTaskResponse> listTasks(Long userId, int page, int size,
                                                              String status, String adapterCode,
                                                              String connectionCode, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        Long connectionId = connectionId(companyId, connectionCode);
        LambdaQueryWrapper<BankDataSyncTask> query = new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(connectionId != null, BankDataSyncTask::getConnectionId, connectionId)
                .eq(connectionCode != null && !connectionCode.isBlank() && connectionId == null,
                        BankDataSyncTask::getConnectionId, -1L)
                .eq(requestId != null && !requestId.isBlank(), BankDataSyncTask::getRequestId,
                        requestId == null ? null : requestId.trim())
                .eq(status != null && !status.isBlank(), BankDataSyncTask::getStatus, normalize(status, null))
                .eq(adapterCode != null && !adapterCode.isBlank(), BankDataSyncTask::getAdapterCode, normalize(adapterCode, null))
                .orderByDesc(BankDataSyncTask::getCreatedAt)
                .orderByDesc(BankDataSyncTask::getId);
        Page<BankDataSyncTask> result = taskMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.tasks(result.getRecords(), companyId));
    }

    private Long connectionId(long companyId, String connectionCode) {
        if (connectionCode == null || connectionCode.isBlank()) return null;
        ConnectionProfile profile = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getCompanyId, companyId)
                .eq(ConnectionProfile::getConnectionCode, connectionCode.trim()));
        return profile == null ? null : profile.getId();
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
                .stream().map(responseAssembler::log).toList();
        return new BankDataSyncTaskDetailResponse(responseAssembler.task(task,
                responseAssembler.connectionCode(companyId, task.getConnectionId())), logs);
    }

    public PageResponse<BankDataStatementResponse> listStatements(Long userId, int page, int size,
                                                                    Long bankAccountId, String direction,
                                                                    LocalDateTime from, LocalDateTime to) {
        return listStatements(userId, page, size, bankAccountId, direction, from, to, null, null);
    }

    public PageResponse<BankDataStatementResponse> listStatements(Long userId, int page, int size,
                                                                    Long bankAccountId, String direction,
                                                                    LocalDateTime from, LocalDateTime to,
                                                                    String taskNo, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<Long> taskIds = scopedTaskIds(companyId, taskNo, requestId);
        if ((taskNo != null && !taskNo.isBlank() || requestId != null && !requestId.isBlank())
                && taskIds.isEmpty()) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .in(!taskIds.isEmpty(), BankDataStatement::getTaskId, taskIds)
                .eq(direction != null && !direction.isBlank(), BankDataStatement::getDirection, normalize(direction, null))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId);
        Page<BankDataStatement> result = statementMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.statements(result.getRecords(), companyId));
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
                .stream().map(responseAssembler::log).toList();
        return new BankDataStatementDetailResponse(responseAssembler.statement(statement, companyId),
                responseAssembler.task(task, task == null ? null : responseAssembler.connectionCode(companyId, task.getConnectionId())), logs);
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

    /**
     * Idempotent reuse is still a success from the caller's perspective, but a new
     * request id arriving on a syncKey hit must not disappear from the audit trail
     * (decision D7 option A). The log row keeps the caller's request id and points
     * at the reused task so trace lookups by either id stay consistent.
     */
    private void recordTaskReused(BankDataSyncTask reusedTask, String requestedRequestId) {
        if (requestedRequestId == null || requestedRequestId.equals(reusedTask.getRequestId())) {
            return;
        }
        BankDataSyncLog log = new BankDataSyncLog();
        log.setCompanyId(reusedTask.getCompanyId());
        log.setTaskId(reusedTask.getId());
        log.setLevel("INFO");
        log.setEventType("TASK_REUSED");
        log.setResult("REUSED");
        log.setRequestId(requestedRequestId);
        log.setMessage("Idempotent reuse: request id " + requestedRequestId
                + " resolved to existing task " + reusedTask.getTaskNo()
                + " (original request id " + reusedTask.getRequestId() + ")");
        logMapper.insert(log);
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private SyncWindow parseWindow(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        LocalDateTime start = requestedStart == null ? LocalDateTime.of(2026, 8, 27, 0, 0) : requestedStart;
        LocalDateTime end = requestedEnd == null ? start.plusDays(1) : requestedEnd;
        if (!end.isAfter(start)) {
            throw new BusinessException(400, "Bank data sync window end must be after start");
        }
        return new SyncWindow(start, end);
    }

    private SyncWindow parseWindow(String requestedStart, String requestedEnd) {
        try {
            LocalDateTime start = requestedStart == null || requestedStart.isBlank() ? null : LocalDateTime.parse(requestedStart.trim());
            LocalDateTime end = requestedEnd == null || requestedEnd.isBlank() ? null : LocalDateTime.parse(requestedEnd.trim());
            return parseWindow(start, end);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(400, "Bank sync window must use ISO date-time format");
        }
    }

    private String syncKey(Long bankAccountId, Long connectionId, String adapterCode, SyncWindow window) {
        return bankAccountId + ":" + (connectionId == null ? 0 : connectionId) + ":" + adapterCode + ":"
                + window.start() + ":" + window.end();
    }

    private long boundedSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    private String safeMessage(RuntimeException exception) {
        if (!(exception instanceof BusinessException)) {
            return "Bank data synchronization failed during internal processing";
        }
        String message = responseAssembler.sanitize(exception.getMessage());
        return message == null || message.isBlank()
                ? "Bank data synchronization failed"
                : message.substring(0, Math.min(500, message.length()));
    }
}
