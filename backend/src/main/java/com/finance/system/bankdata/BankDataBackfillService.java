package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * W17 包 F（2026-09-23 晚拍板 #6）：历史数据回补编排——银行接入日以前的流水与按日余额。
 *
 * <p><b>执行模型</b>：既有 {@code BankDataSyncService.trigger} 是同步逐片执行（executor
 * 内联跑完才返回），一个 3 年回补会有 36+ 片 × 双查询，HTTP 同步等待必然超时。本服务采用
 * 「受理即返回 + 后台单线程推进」：POST 端点先插入一条受理任务并交给单线程执行器，逐账户
 * 串行、片间 sleep（防银行风控）；进度通过既有 bank_data_sync_task 的 BACKFILL 记录观测，
 * 汇总结果落回受理任务的 error_message/日志。</p>
 *
 * <p><b>零改动既有管道</b>：流水片复用 {@link BankDataSyncService#triggerForCompany} 的
 * BACKFILL trigger type（同步窗口显式传历史区间，覆盖 parseWindow 的默认起点）；历史余额
 * 片调 adapter 的 {@code collectHistoryBalance}（default 方法，Mock 返回空成功），由本服务
 * 直接落 bank_data_balance，去重键与既有 {@code BankDataSyncExecutor#balanceKey} 完全一致
 * （accountId|asOfTime + 库唯一键 uk_bank_balance_snapshot），同日重跑幂等。</p>
 */
@Service
public class BankDataBackfillService {

    private static final Logger log = LoggerFactory.getLogger(BankDataBackfillService.class);

    /** 流水分片长度（天）:统一 90 天，留出中信银行侧 92 天硬校验余量。 */
    static final int STATEMENT_CHUNK_DAYS = 90;
    /** 余额切片长度（天）:中信 DLHBLQRY ≤30 天；招行 NTQABINF ≤31 天 → 统一 30 天。 */
    static final int BALANCE_CHUNK_DAYS = 30;

    private final BankDataSyncService syncService;
    private final CompanyScopeService companyScope;
    private final BankAccountMapper bankAccountMapper;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankDataAdapterRegistryAccessor adapterAccessor;
    private final long chunkIntervalMillis;
    private final java.util.concurrent.ExecutorService worker;
    private final boolean ownsWorker;

    /** Adapter lookup seam: keeps this service testable without a full aggregation stack. */
    interface BankDataAdapterRegistryAccessor {
        BankDataAdapter require(String adapterCode);
    }

    public BankDataBackfillService(BankDataSyncService syncService,
                                   CompanyScopeService companyScope,
                                   BankAccountMapper bankAccountMapper,
                                   BankDataSyncTaskMapper taskMapper,
                                   BankDataBalanceMapper balanceMapper,
                                   BankDataRawMessageMapper rawMessageMapper,
                                   BankDataSyncLogMapper logMapper,
                                   com.finance.system.bankdata.aggregation.BankDataAdapterRegistry adapterRegistry) {
        this(syncService, companyScope, bankAccountMapper, taskMapper, balanceMapper, rawMessageMapper,
                logMapper, adapterRegistry::require, 2000L,
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "bank-data-backfill");
                    thread.setDaemon(true);
                    return thread;
                }), true);
    }

    /** Full-form constructor for tests (injectable interval, replaceable worker). */
    BankDataBackfillService(BankDataSyncService syncService,
                            CompanyScopeService companyScope,
                            BankAccountMapper bankAccountMapper,
                            BankDataSyncTaskMapper taskMapper,
                            BankDataBalanceMapper balanceMapper,
                            BankDataRawMessageMapper rawMessageMapper,
                            BankDataSyncLogMapper logMapper,
                            BankDataAdapterRegistryAccessor adapterAccessor,
                            long chunkIntervalMillis,
                            java.util.concurrent.ExecutorService worker,
                            boolean ownsWorker) {
        this.syncService = syncService;
        this.companyScope = companyScope;
        this.bankAccountMapper = bankAccountMapper;
        this.taskMapper = taskMapper;
        this.balanceMapper = balanceMapper;
        this.rawMessageMapper = rawMessageMapper;
        this.logMapper = logMapper;
        this.adapterAccessor = adapterAccessor;
        this.chunkIntervalMillis = chunkIntervalMillis;
        this.worker = worker;
        this.ownsWorker = ownsWorker;
    }

    // ------------------------------------------------------------------
    // Request model & summary
    // ------------------------------------------------------------------

    /** Backfill request: scope (one account or all) + history range + scope switches. */
    public record BackfillRequest(Long accountId, LocalDate historyStart, LocalDate historyEnd,
                                  boolean statements, boolean balances) {

        public BackfillRequest {
            if (historyStart == null) {
                throw new BusinessException(400, "Backfill historyStart is required");
            }
            if (historyEnd == null) {
                // 默认回补到昨日：与「历史数据 = 早于当日」的银行侧约束保持一致。
                historyEnd = LocalDate.now().minusDays(1);
            }
            if (historyEnd.isBefore(historyStart)) {
                throw new BusinessException(400, "Backfill historyEnd must not be before historyStart");
            }
            if (!statements && !balances) {
                throw new BusinessException(400, "Backfill requires at least one of statements or balances");
            }
        }

        /** Compact constructor without an explicit end (defaults to yesterday). */
        public BackfillRequest(Long accountId, LocalDate historyStart, boolean statements, boolean balances) {
            this(accountId, historyStart, null, statements, balances);
        }
    }

    /** Per-run aggregate: chunk counts plus pulled row totals. */
    public record BackfillSummary(Long runTaskId, int accounts, int statementChunks, int statementChunkFailures,
                                  int balanceChunks, int balanceChunkFailures, long rows) {
    }

    private record ChunkResult(boolean succeeded, long rows) {
    }

    // ------------------------------------------------------------------
    // Async entry point (endpoint)
    // ------------------------------------------------------------------

    /**
     * 受理并后台推进。返回受理任务 id（bank_data_sync_task 里 triggerType=BACKFILL、
     * requestId=backfill:run:{...} 的汇总行，error_message 存汇总文本）。
     */
    public Long submitAsync(Long userId, BackfillRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        List<BankAccount> accounts = resolveAccounts(companyId, request.accountId());
        validateAdapters(companyId, accounts);
        String runRequestId = boundRequestId("backfill:run:" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24));
        BankDataSyncTask runTask = insertRunTask(companyId, userId, accounts, request, runRequestId);
        worker.submit(() -> {
            try {
                BackfillSummary summary = runBackfill(companyId, userId, accounts, request);
                finishRunTask(runTask, summary);
            } catch (RuntimeException exception) {
                log.error("Bank data backfill run {} failed", runRequestId, exception);
                runTask.setStatus("FAILED");
                runTask.setErrorMessage(safeMessage(exception));
                runTask.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(runTask);
            }
        });
        return runTask.getId();
    }

    /** Synchronous execution — used by tests and available for scripted runs. */
    public BackfillSummary runBackfill(long companyId, Long userId, List<BankAccount> accounts,
                                       BackfillRequest request) {
        int statementChunks = 0;
        int statementFailures = 0;
        int balanceChunks = 0;
        int balanceFailures = 0;
        long rows = 0;
        boolean first = true;
        for (BankAccount account : accounts) {
            if (!first) {
                sleepBetweenChunks();
            }
            first = false;
            if (request.statements()) {
                for (Chunk chunk : statementChunks(request.historyStart(), request.historyEnd())) {
                    ChunkResult result = runStatementChunk(companyId, userId, account, chunk);
                    statementChunks++;
                    if (result.succeeded()) {
                        rows += result.rows();
                    } else {
                        statementFailures++;
                    }
                    sleepBetweenChunks();
                }
            }
            if (request.balances()) {
                for (Chunk chunk : balanceChunks(request.historyStart(), request.historyEnd())) {
                    ChunkResult result = runBalanceChunk(companyId, userId, account, chunk);
                    balanceChunks++;
                    if (result.succeeded()) {
                        rows += result.rows();
                    } else {
                        balanceFailures++;
                    }
                    sleepBetweenChunks();
                }
            }
        }
        return new BackfillSummary(null, accounts.size(), statementChunks, statementFailures,
                balanceChunks, balanceFailures, rows);
    }

    // ------------------------------------------------------------------
    // Statement chunks (reuse the existing pipeline)
    // ------------------------------------------------------------------

    private ChunkResult runStatementChunk(long companyId, Long userId, BankAccount account, Chunk chunk) {
        String requestId = boundRequestId("backfill:" + account.getId() + ":" + chunk.start() + ":" + chunk.end());
        try {
            var detail = syncService.triggerForCompany(companyId, userId,
                    new com.finance.system.bankdata.dto.BankDataSyncRequest(null, account.getId(), null,
                            chunk.start().atStartOfDay(),
                            chunk.end().plusDays(1).atStartOfDay().minusNanos(1)),
                    requestId, "BACKFILL");
            String status = detail.task().status();
            boolean succeeded = "SUCCEEDED".equals(status) || "PARTIAL".equals(status)
                    || "DUPLICATE".equals(status);
            return new ChunkResult(succeeded, detail.task().rawCount() == null ? 0 : detail.task().rawCount());
        } catch (RuntimeException exception) {
            log.warn("Backfill statement chunk {} for account {} failed: {}", chunk, account.getId(),
                    exception.getMessage());
            return new ChunkResult(false, 0);
        }
    }

    // ------------------------------------------------------------------
    // Balance chunks (direct adapter call + same dedup pipeline keys)
    // ------------------------------------------------------------------

    private ChunkResult runBalanceChunk(long companyId, Long userId, BankAccount account, Chunk chunk) {
        String adapterCode = account.getBankCode() == null ? "" : account.getBankCode().trim()
                .toUpperCase(Locale.ROOT);
        // 与流水片 requestId 同格式但多一段 balance 前缀：uk(company_id, request_id) 下
        // 两条管道（triggerForCompany / 本服务直落库）绝不能互相误复用任务。
        String requestId = boundRequestId("backfill:balance:" + account.getId() + ":" + chunk.start()
                + ":" + chunk.end());
        // 重放幂等：同 requestId（同账户同切片）已有完结任务 → 直接复用，不再打银行。
        BankDataSyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(BankDataSyncTask::getRequestId, requestId));
        if (existing != null) {
            if ("RUNNING".equals(existing.getStatus())) {
                return new ChunkResult(false, 0);
            }
            return new ChunkResult(true, existing.getRawCount() == null ? 0 : existing.getRawCount());
        }
        BankDataSyncTask task = null;
        try {
            BankDataAdapter adapter = adapterAccessor.require(adapterCode);
            task = insertBalanceChunkTask(companyId, userId, account, adapterCode, chunk, requestId);
            BankDataSyncContext context = new BankDataSyncContext(companyId, null, account.getId(),
                    task.getTaskNo(), requestId, chunk.start().atStartOfDay(),
                    chunk.end().plusDays(1).atStartOfDay().minusNanos(1), 1, null, 100, "BALANCE");
            BankDataCollection collection = adapter.collectHistoryBalance(context);
            String status = collection.status();
            if (!"SUCCESS".equals(status)) {
                task.setStatus(status == null ? "UNKNOWN" : status);
                task.setErrorMessage("Bank history balance response status " + status);
                task.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(task);
                return new ChunkResult(false, 0);
            }
            long inserted = persistHistoricalBalances(companyId, task, collection);
            task.setStatus("SUCCEEDED");
            task.setRawCount(collection.balances() == null ? 0 : collection.balances().size());
            task.setNormalizedCount((int) inserted);
            task.setDuplicateCount(collection.balances() == null ? 0 : collection.balances().size() - (int) inserted);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log(task, "INFO", "BACKFILL_BALANCE_CHUNK", "SUCCEEDED",
                    "Historical balance chunk " + chunk + " persisted " + inserted + " snapshot(s)");
            return new ChunkResult(true, collection.balances() == null ? 0 : collection.balances().size());
        } catch (RuntimeException exception) {
            log.warn("Backfill balance chunk {} for account {} failed: {}", chunk, account.getId(),
                    exception.getMessage());
            if (task != null && !"SUCCEEDED".equals(task.getStatus())) {
                task.setStatus("FAILED");
                task.setErrorMessage(safeMessage(exception));
                task.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(task);
            }
            return new ChunkResult(false, 0);
        }
    }

    /**
     * 与 BankDataSyncExecutor 同一落库口径：balanceKey = accountId|asOfTime，库唯一键
     * uk_bank_balance_snapshot(company_id, bank_account_id, as_of_time) 兜底，同日重跑幂等。
     * 历史余额快照行没有独立 raw_message 报文语义时不强造——这里直接以适配器返回集合落表，
     * raw_message_id 允许指向本任务行自己的占位 raw（保持 NOT NULL 约束），bankRequestNo
     * 保留银行侧请求号以便追溯。
     */
    private long persistHistoricalBalances(long companyId, BankDataSyncTask task,
                                           BankDataCollection collection) {
        List<BankDataBalanceEntry> balances = collection.balances() == null ? List.of() : collection.balances();
        if (balances.isEmpty()) {
            return 0;
        }
        BankDataRawMessage raw = insertBalancePlaceholderRaw(companyId, task, collection);
        long inserted = 0;
        for (BankDataBalanceEntry entry : balances) {
            if (entry.asOfTime() == null || entry.availableBalance() == null) {
                continue;
            }
            if (balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                    .eq(BankDataBalance::getCompanyId, companyId)
                    .eq(BankDataBalance::getBankAccountId, entry.bankAccountId())
                    .eq(BankDataBalance::getAsOfTime, entry.asOfTime())) > 0) {
                continue;
            }
            BankDataBalance balance = new BankDataBalance();
            balance.setCompanyId(companyId);
            balance.setTaskId(task.getId());
            balance.setRawMessageId(raw.getId());
            balance.setBankAccountId(entry.bankAccountId());
            balance.setBankRequestNo(entry.bankRequestNo() == null ? collection.bankRequestNo()
                    : entry.bankRequestNo());
            balance.setAvailableBalance(entry.availableBalance().setScale(2));
            balance.setCurrency(entry.currency() == null || entry.currency().isBlank()
                    ? "CNY" : entry.currency().trim().toUpperCase(Locale.ROOT));
            balance.setAsOfTime(entry.asOfTime());
            balance.setOnlineBalance(scaled(entry.onlineBalance()));
            balance.setFrozenBalance(scaled(entry.frozenBalance()));
            balance.setPreviousDayBalance(scaled(entry.previousDayBalance()));
            balance.setVendorCurrencyCode(blankToNull(entry.vendorCurrencyCode()));
            balance.setBranchCode(blankToNull(entry.branchCode()));
            balance.setBankAccountNo(blankToNull(entry.bankAccountNo()));
            balance.setBankAccountName(blankToNull(entry.bankAccountName()));
            balance.setAccountItem(blankToNull(entry.accountItem()));
            balance.setCustomerRelationNo(blankToNull(entry.customerRelationNo()));
            balance.setAccountStatus(blankToNull(entry.accountStatus()));
            balance.setOpenDate(blankToNull(entry.openDate()));
            balance.setInterestType(blankToNull(entry.interestType()));
            balance.setDepositTerm(blankToNull(entry.depositTerm()));
            balance.setOverdraftLimit(scaled(entry.overdraftLimit()));
            balance.setInterestCode(blankToNull(entry.interestCode()));
            balance.setInterestRate(entry.interestRate());
            balance.setMaturityDate(blankToNull(entry.maturityDate()));
            balance.setValidationStatus("VALID");
            try {
                balanceMapper.insert(balance);
                inserted++;
            } catch (DuplicateKeyException duplicateKeyException) {
                // uk_bank_balance_snapshot 兜底：并发/重放场景下同键行已存在。
            }
        }
        return inserted;
    }

    private BankDataRawMessage insertBalancePlaceholderRaw(long companyId, BankDataSyncTask task,
                                                           BankDataCollection collection) {
        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(companyId);
        raw.setTaskId(task.getId());
        raw.setAdapterCode(task.getAdapterCode());
        raw.setMappingVersion(task.getMappingVersion() == null ? "FINFLOW-BANKDATA-V1" : task.getMappingVersion());
        raw.setBankRequestNo(collection.bankRequestNo());
        String payload = "BACKFILL_BALANCE:" + (collection.balances() == null ? 0 : collection.balances().size());
        raw.setPayload(payload);
        raw.setContentSha256(sha256(payload));
        raw.setReceivedAt(LocalDateTime.now());
        raw.setRetentionUntil(raw.getReceivedAt().plusDays(30));
        rawMessageMapper.insert(raw);
        return raw;
    }

    // ------------------------------------------------------------------
    // Chunking
    // ------------------------------------------------------------------

    record Chunk(LocalDate start, LocalDate end) {
    }

    /** 流水切片:≤90 天（中信银行侧 92 天硬校验留余量），区间含两端。 */
    static List<Chunk> statementChunks(LocalDate start, LocalDate end) {
        return chunk(start, end, STATEMENT_CHUNK_DAYS);
    }

    /** 余额切片:≤30 天、上界最多到昨日（招行 NTQABINF 必须早于当日）。 */
    static List<Chunk> balanceChunks(LocalDate start, LocalDate end) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate effectiveEnd = end.isAfter(yesterday) ? yesterday : end;
        if (effectiveEnd.isBefore(start)) {
            return List.of();
        }
        return chunk(start, effectiveEnd, BALANCE_CHUNK_DAYS);
    }

    private static List<Chunk> chunk(LocalDate start, LocalDate end, int maxDays) {
        List<Chunk> chunks = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate chunkEnd = cursor.plusDays(maxDays);
            if (chunkEnd.isAfter(end)) {
                chunkEnd = end;
            }
            chunks.add(new Chunk(cursor, chunkEnd));
            cursor = chunkEnd.plusDays(1);
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // Run task bookkeeping
    // ------------------------------------------------------------------

    private List<BankAccount> resolveAccounts(long companyId, Long accountId) {
        if (accountId != null) {
            BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                    .eq(BankAccount::getId, accountId)
                    .eq(BankAccount::getCompanyId, companyId));
            if (account == null) {
                throw new BusinessException(404, "Bank account not found in the current company");
            }
            return List.of(account);
        }
        List<BankAccount> accounts = bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, companyId)
                .eq(BankAccount::getStatus, "ACTIVE")
                .orderByAsc(BankAccount::getId));
        if (accounts.isEmpty()) {
            throw new BusinessException(404, "No active bank account in the current company");
        }
        return accounts;
    }

    private void validateAdapters(long companyId, List<BankAccount> accounts) {
        for (BankAccount account : accounts) {
            String code = account.getBankCode() == null ? "" : account.getBankCode().trim();
            if (code.isEmpty()) {
                throw new BusinessException(400, "Bank account " + account.getId() + " has no bank code");
            }
            try {
                adapterAccessor.require(code);
            } catch (RuntimeException exception) {
                throw new BusinessException(400, "Bank data adapter is not available for account "
                        + account.getId() + " (" + code + ")");
            }
        }
    }

    private BankDataSyncTask insertRunTask(long companyId, Long userId, List<BankAccount> accounts,
                                           BackfillRequest request, String runRequestId) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-BACKFILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(Locale.ROOT));
        task.setAdapterCode(accounts.size() == 1 ? safeAdapterCode(accounts.get(0)) : "BACKFILL");
        task.setBankAccountId(accounts.get(0).getId());
        task.setRequestedBy(userId);
        task.setRequestId(runRequestId);
        task.setSyncKey("backfill-run:" + runRequestId);
        task.setTriggerType("BACKFILL");
        task.setWindowStart(request.historyStart().atStartOfDay());
        task.setWindowEnd(request.historyEnd().plusDays(1).atStartOfDay().minusNanos(1));
        task.setStatus("RUNNING");
        task.setRawCount(0);
        task.setNormalizedCount(0);
        task.setDuplicateCount(0);
        task.setInvalidCount(0);
        taskMapper.insert(task);
        return task;
    }

    private BankDataSyncTask insertBalanceChunkTask(long companyId, Long userId, BankAccount account,
                                                    String adapterCode, Chunk chunk, String requestId) {
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(companyId);
        task.setTaskNo("BDST-BFB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14)
                .toUpperCase(Locale.ROOT));
        task.setAdapterCode(adapterCode);
        task.setMappingVersion("FINFLOW-BANKDATA-V1");
        task.setBankAccountId(account.getId());
        task.setRequestedBy(userId);
        task.setRequestId(requestId);
        task.setSyncKey(boundRequestId("backfill-balance:" + account.getId() + ":" + chunk.start()
                + ":" + chunk.end()));
        task.setTriggerType("BACKFILL");
        task.setWindowStart(chunk.start().atStartOfDay());
        task.setWindowEnd(chunk.end().plusDays(1).atStartOfDay().minusNanos(1));
        task.setStatus("RUNNING");
        task.setRawCount(0);
        task.setNormalizedCount(0);
        task.setDuplicateCount(0);
        task.setInvalidCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private void finishRunTask(BankDataSyncTask runTask, BackfillSummary summary) {
        boolean ok = summary.statementChunkFailures() == 0 && summary.balanceChunkFailures() == 0;
        runTask.setStatus(ok ? "SUCCEEDED" : "PARTIAL");
        runTask.setRawCount((int) Math.min(Integer.MAX_VALUE, summary.rows()));
        String message = "Backfill done: accounts=" + summary.accounts()
                + ", statementChunks=" + summary.statementChunks()
                + " (failed " + summary.statementChunkFailures() + ")"
                + ", balanceChunks=" + summary.balanceChunks()
                + " (failed " + summary.balanceChunkFailures() + ")"
                + ", rows=" + summary.rows();
        runTask.setErrorMessage(message.length() > 500 ? message.substring(0, 500) : message);
        runTask.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(runTask);
        log(runTask, "INFO", "BACKFILL_RUN_COMPLETED", runTask.getStatus(), message);
    }

    private void log(BankDataSyncTask task, String level, String eventType, String result, String message) {
        BankDataSyncLog entry = new BankDataSyncLog();
        entry.setCompanyId(task.getCompanyId());
        entry.setTaskId(task.getId());
        entry.setLevel(level);
        entry.setEventType(eventType);
        entry.setResult(result);
        entry.setRequestId(task.getRequestId());
        entry.setMessage(message == null || message.length() <= 500 ? message : message.substring(0, 500));
        logMapper.insert(entry);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void sleepBetweenChunks() {
        if (chunkIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(chunkIntervalMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(409, "Backfill was interrupted");
        }
    }

    /** 应用关闭时不再受理新的回补片；已在跑的片随 daemon 线程终止（进度行留在 RUNNING）。 */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (ownsWorker) {
            worker.shutdownNow();
        }
    }

    /** request_id 列宽 64（V4）:超长截断保后缀唯一性。 */
    private static String boundRequestId(String value) {
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String safeAdapterCode(BankAccount account) {
        String code = account.getBankCode() == null ? "" : account.getBankCode().trim()
                .toUpperCase(Locale.ROOT);
        return code.isEmpty() ? "BACKFILL" : code;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Bank data backfill failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte part : digest) result.append(String.format("%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
