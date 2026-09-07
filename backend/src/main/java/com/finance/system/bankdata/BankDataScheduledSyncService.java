package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Coordinates scheduled scans; task creation and execution remain in the sync service.
 *
 * <p>Account-driven scan (2026-09-07): the previous implementation iterated
 * {@code connection_profile} rows, but that table is optional bookkeeping and was empty in
 * production — the scheduler silently did nothing. {@code bank_account} is the real carrier of
 * "who we pull for": every ACTIVE account of every company is scanned, routed by its
 * {@code bank_code}. Only banks whose REAL adapter is actually wired are auto-pulled; anything
 * else fails closed to "no automatic pull", consistent with the registry's routing-only,
 * no-simulated-fallback semantics.</p>
 */
@Service
public class BankDataScheduledSyncService {

    private final BankAccountMapper bankAccountMapper;
    private final BankDataAdapterRegistry registry;
    private final BankDataSyncService syncService;

    public BankDataScheduledSyncService(BankAccountMapper bankAccountMapper,
                                        BankDataAdapterRegistry registry,
                                        BankDataSyncService syncService) {
        this.bankAccountMapper = bankAccountMapper;
        this.registry = registry;
        this.syncService = syncService;
    }

    public void triggerScheduledSyncs() {
        Window window = yesterdayWindow();
        List<BankAccount> accounts = bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getStatus, "ACTIVE")
                .orderByAsc(BankAccount::getCompanyId)
                .orderByAsc(BankAccount::getId));
        for (BankAccount account : accounts) {
            if (account.getCompanyId() == null) continue;
            String adapterCode = adapterCodeOf(account);
            if (adapterCode == null || !registry.isRealProvider(adapterCode)) continue;
            try {
                String requestId = scheduledRequestId(account, adapterCode, window);
                syncService.triggerForCompany(account.getCompanyId(), null,
                        new BankDataSyncRequest(null, account.getId(), adapterCode,
                                window.start(), window.end()), requestId, "SCHEDULED");
            } catch (RuntimeException ignored) {
                // The sync service persists task failures; one account must not stop the scan.
            }
        }
    }

    /** Route by the account's own bank code (e.g. CMB); blank codes never match a real adapter. */
    private String adapterCodeOf(BankAccount account) {
        String bankCode = account.getBankCode();
        return bankCode == null || bankCode.isBlank() ? null : bankCode.trim().toUpperCase(Locale.ROOT);
    }

    private String scheduledRequestId(BankAccount account, String adapterCode, Window window) {
        String key = account.getCompanyId() + ":" + account.getId()
                + ":" + adapterCode + ":" + window.start() + ":" + window.end();
        return "scheduled-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private Window yesterdayWindow() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        return new Window(LocalDateTime.of(yesterday, LocalTime.MIDNIGHT),
                LocalDateTime.of(yesterday.plusDays(1), LocalTime.MIDNIGHT));
    }

    private record Window(LocalDateTime start, LocalDateTime end) {}
}
