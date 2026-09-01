package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.aggregation.BankDataAggregationService;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.bankdata.dto.BankSyncJobDetailResponse;
import com.finance.system.bankdata.dto.BankSyncJobEventResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.bankdata.dto.BankSyncJobTriggerRequest;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Write side of the bank data pipeline: resolving adapters, enforcing request
 * id / sync key idempotency, persisting tasks and delegating execution to
 * {@link BankDataSyncExecutor}. Reads live in {@link BankDataQueryService}.
 */
@Service
public class BankDataSyncService {

    private final CompanyScopeService companyScope;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ConnectionProfileMapper connectionProfileMapper;
    private final BankDataSyncExecutor executor;
    private final BankDataSyncEvidenceService evidenceService;
    private final BankDataSyncResponseAssembler responseAssembler;
    private final BankDataScheduledSyncService scheduledSyncService;
    private final BankDataAggregationService aggregationService;
    private final BankDataQueryService queryService;

    private record SyncWindow(LocalDateTime start, LocalDateTime end) {}

    public BankDataSyncService(CompanyScopeService companyScope,
                               BankDataSyncTaskMapper taskMapper,
                               BankDataSyncLogMapper logMapper,
                               BankAccountMapper bankAccountMapper,
                               ConnectionProfileMapper connectionProfileMapper,
                               BankDataSyncExecutor executor,
                               BankDataSyncEvidenceService evidenceService,
                               BankDataSyncResponseAssembler responseAssembler,
                               @Lazy BankDataScheduledSyncService scheduledSyncService,
                               BankDataAggregationService aggregationService,
                               BankDataQueryService queryService) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.connectionProfileMapper = connectionProfileMapper;
        this.executor = executor;
        this.evidenceService = evidenceService;
        this.responseAssembler = responseAssembler;
        this.scheduledSyncService = scheduledSyncService;
        this.aggregationService = aggregationService;
        this.queryService = queryService;
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
            return queryService.getTaskDetail(existing.getId(), companyId);
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
            return queryService.getTaskDetail(running.getId(), companyId);
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
            return queryService.getTaskDetail(concurrent.getId(), companyId);
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
        return queryService.getTaskDetail(task.getId(), companyId);
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
