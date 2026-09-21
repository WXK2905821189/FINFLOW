package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BankDataScheduledSyncService.class);

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
        int dispatched = 0;
        int rejected = 0;
        for (BankAccount account : accounts) {
            if (account.getCompanyId() == null) continue;
            String adapterCode = adapterCodeOf(account);
            if (adapterCode == null || !registry.isRealProvider(adapterCode)) continue;
            try {
                String requestId = scheduledRequestId(account, adapterCode, window);
                syncService.triggerForCompany(account.getCompanyId(), null,
                        new BankDataSyncRequest(null, account.getId(), adapterCode,
                                window.start(), window.end()), requestId, "SCHEDULED");
                dispatched++;
            } catch (RuntimeException exception) {
                // 2026-09-21：原先 `catch (RuntimeException ignored) {}` 连「同账户同窗口已在跑（409）」
                // 都静默吞掉 —— 计划到点却「什么都没发生」时完全无从排查。现至少留下 WARN 痕迹。
                rejected++;
                log.warn("bank sync schedule dispatch rejected: company={} account={} adapter={} window={}~{} : {}",
                        account.getCompanyId(), account.getId(), adapterCode,
                        window.start(), window.end(), exception.getMessage());
            }
        }
        // 注意：dispatched 只代表「请求已交给同步服务」，同一自然日的第二个计划时刻会因
        // requestId（含 T-1 全天窗口）幂等而被复用（bank_data_sync_log 记 TASK_REUSED），
        // 不会新建任务、也不会重新调银行 —— 这是设计语义，不是失败。
        log.info("bank sync schedule scan done: accounts={} dispatched={} rejected={} window={}~{}",
                accounts.size(), dispatched, rejected, window.start(), window.end());
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
