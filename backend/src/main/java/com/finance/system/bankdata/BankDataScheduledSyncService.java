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
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

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
        triggerScheduledSyncs(currentMinute());
    }

    /**
     * 2026-09-23 语义修正：requestId 加入触发时刻（HH:mm），同一天的不同计划时刻各自真实执行。
     * 旧行为（key 只含 T-1 全天窗口）导致同日第二个时刻算出相同 requestId 被 TASK_REUSED
     * 静默复用，表现为「设置了计划但一次都没跑」。重复数据风险由既有去重层兜底
     * （uk company+requestId、statementKey/balanceKey 唯一键，重复行记 STATEMENT_DEDUPLICATED）。
     */
    public void triggerScheduledSyncs(LocalDateTime triggerAt) {
        Window window = yesterdayWindow(triggerAt.toLocalDate());
        String triggerHhmm = triggerAt.format(HHMM);
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
                String requestId = scheduledRequestId(account, adapterCode, triggerHhmm, window);
                syncService.triggerForCompany(account.getCompanyId(), null,
                        new BankDataSyncRequest(null, account.getId(), adapterCode,
                                window.start(), window.end()), requestId, "SCHEDULED");
                dispatched++;
            } catch (RuntimeException exception) {
                // 2026-09-21：原先 `catch (RuntimeException ignored) {}` 连「同账户同窗口已在跑（409）」
                // 都静默吞掉 —— 计划到点却「什么都没发生」时完全无从排查。现至少留下 WARN 痕迹。
                rejected++;
                log.warn("bank sync schedule dispatch rejected: company={} account={} adapter={} trigger={} window={}~{} : {}",
                        account.getCompanyId(), account.getId(), adapterCode, triggerHhmm,
                        window.start(), window.end(), exception.getMessage());
            }
        }
        // 2026-09-23：requestId 已含触发时刻，同一自然日的不同计划时刻各建各的任务、各拉一轮。
        // 同一 requestId 的重放（同分钟重试/心跳重复命中）仍幂等复用；同账户同窗口并发仍由
        // 既有 409 护栏拦截。多时刻对同一 T-1 窗口的重复流水由去重层跳过（STATEMENT_DEDUPLICATED）。
        log.info("bank sync schedule scan done: accounts={} dispatched={} rejected={} trigger={} window={}~{}",
                accounts.size(), dispatched, rejected, triggerHhmm, window.start(), window.end());
    }

    /** 心跳每分钟命中一次；取当前分钟（截断秒/纳秒）作为触发时刻，保证「同一分钟内只触发一次」语义不变。 */
    private LocalDateTime currentMinute() {
        return LocalDateTime.now().withSecond(0).withNano(0);
    }

    /** Route by the account's own bank code (e.g. CMB); blank codes never match a real adapter. */
    private String adapterCodeOf(BankAccount account) {
        String bankCode = account.getBankCode();
        return bankCode == null || bankCode.isBlank() ? null : bankCode.trim().toUpperCase(Locale.ROOT);
    }

    private String scheduledRequestId(BankAccount account, String adapterCode, String triggerHhmm, Window window) {
        String key = account.getCompanyId() + ":" + account.getId()
                + ":" + adapterCode + ":" + triggerHhmm + ":" + window.start() + ":" + window.end();
        return "scheduled-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private Window yesterdayWindow(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        return new Window(LocalDateTime.of(yesterday, LocalTime.MIDNIGHT),
                LocalDateTime.of(yesterday.plusDays(1), LocalTime.MIDNIGHT));
    }

    private record Window(LocalDateTime start, LocalDateTime end) {}
}
