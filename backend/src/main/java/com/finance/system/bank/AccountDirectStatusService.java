package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-account "is this bank account really direct-connected?" fact source.
 *
 * <p>A bank connection is granted per bank, but queryability is per account: CMB interfaces
 * take the bank account number ({@code accnbr}) as the request key and each account succeeds or
 * fails independently. A provider-level switch alone therefore cannot answer "can we really pull
 * data for THIS account", which is exactly what the account list must display.
 *
 * <p>Resolution (two proofs, server-side only):
 * <ol>
 *   <li><b>Assembly proof</b> - the account's {@code bankCode} has a REAL-mode adapter registered
 *       ({@link BankDataAdapterRegistry#realAdapterCodes()}). A real adapter bean only exists when
 *       its per-bank switch is on, so absence means "this bank is not wired", full stop.</li>
 *   <li><b>Heartbeat proof</b> - the account has at least one SUCCEEDED sync task executed by a
 *       real adapter ({@code bank_data_sync_task}). This is empirical evidence that the account
 *       number was accepted by the bank.</li>
 * </ol>
 *
 * <p>Outcome: {@link #DIRECT_CONNECTED} (both proofs), {@link #ONBOARDED} (assembled, no real
 * sync yet - explicitly NOT the same as connected), {@link #NOT_CONNECTED} (no real adapter).
 * The bank-side operable-account cross-check (CMB {@code DCLISACC}) is a future enhancement:
 * it is only needed to detect "onboarded locally but not enabled at the bank", which the
 * heartbeat already covers for anything that has actually been queried.
 */
@Service
public class AccountDirectStatusService {

    public static final String DIRECT_CONNECTED = "DIRECT_CONNECTED";
    public static final String ONBOARDED = "ONBOARDED";
    public static final String NOT_CONNECTED = "NOT_CONNECTED";

    private static final String SYNC_STATUS_SUCCESS = "SUCCEEDED";

    private final BankDataAdapterRegistry registry;
    private final BankDataSyncTaskMapper syncTaskMapper;

    public AccountDirectStatusService(BankDataAdapterRegistry registry, BankDataSyncTaskMapper syncTaskMapper) {
        this.registry = registry;
        this.syncTaskMapper = syncTaskMapper;
    }

    /**
     * Batch-resolves the status for a page of accounts with a single aggregate query per call
     * (accounts are few and already tenant-scoped by the caller; this avoids per-account N+1).
     *
     * @return account id → status view; accounts without an id are skipped
     */
    public Map<Long, DirectStatusView> resolve(Collection<BankAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        Set<String> realCodes = registry.realAdapterCodes();
        Set<Long> accountIds = accounts.stream().map(BankAccount::getId)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        Set<Long> companyIds = accounts.stream().map(BankAccount::getCompanyId)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        Map<Long, LocalDateTime> latestRealSync = latestRealSync(accountIds, companyIds, realCodes);

        Map<Long, DirectStatusView> views = new HashMap<>();
        for (BankAccount account : accounts) {
            if (account.getId() == null) continue;
            LocalDateTime lastSync = latestRealSync.get(account.getId());
            views.put(account.getId(), view(realCodes.contains(providerOf(account)), lastSync));
        }
        return views;
    }

    /** Convenience for single-account flows (create/update responses). */
    public DirectStatusView resolveOne(BankAccount account) {
        if (account == null || account.getId() == null) {
            return new DirectStatusView(NOT_CONNECTED, null);
        }
        return resolve(List.of(account)).getOrDefault(account.getId(), new DirectStatusView(NOT_CONNECTED, null));
    }

    private DirectStatusView view(boolean realProvider, LocalDateTime lastRealSyncAt) {
        if (!realProvider) {
            return new DirectStatusView(NOT_CONNECTED, null);
        }
        if (lastRealSyncAt == null) {
            return new DirectStatusView(ONBOARDED, null);
        }
        return new DirectStatusView(DIRECT_CONNECTED, lastRealSyncAt.toString());
    }

    /**
     * Latest successful real-adapter sync per account. Only the id/status/timestamp columns are
     * selected and the result is folded in memory: a per-group LIMIT is not portable across
     * H2 (tests) and MySQL (production), and the filtered row count stays small.
     */
    private Map<Long, LocalDateTime> latestRealSync(Set<Long> accountIds, Set<Long> companyIds, Set<String> realCodes) {
        if (accountIds.isEmpty() || realCodes.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BankDataSyncTask> query = new LambdaQueryWrapper<BankDataSyncTask>()
                .select(BankDataSyncTask::getBankAccountId, BankDataSyncTask::getAdapterCode,
                        BankDataSyncTask::getStatus, BankDataSyncTask::getCompletedAt)
                .in(BankDataSyncTask::getBankAccountId, accountIds)
                .in(BankDataSyncTask::getAdapterCode, realCodes)
                .eq(BankDataSyncTask::getStatus, SYNC_STATUS_SUCCESS);
        if (!companyIds.isEmpty()) {
            query.in(BankDataSyncTask::getCompanyId, companyIds);
        }
        Map<Long, LocalDateTime> latest = new HashMap<>();
        for (BankDataSyncTask task : syncTaskMapper.selectList(query)) {
            if (task.getBankAccountId() == null || task.getCompletedAt() == null) continue;
            latest.merge(task.getBankAccountId(), task.getCompletedAt(),
                    (current, candidate) -> candidate.isAfter(current) ? candidate : current);
        }
        return latest;
    }

    private String providerOf(BankAccount account) {
        String bankCode = account.getBankCode();
        return bankCode == null ? "" : bankCode.trim().toUpperCase(Locale.ROOT);
    }

    /** Status plus the raw evidence timestamp; presentation copy belongs to the client. */
    public record DirectStatusView(String status, String lastRealSyncAt) {
    }
}
