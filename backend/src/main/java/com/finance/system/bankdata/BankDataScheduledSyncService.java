package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.dto.BankDataSyncRequest;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Coordinates scheduled scans; task creation and execution remain in the sync service. */
@Service
public class BankDataScheduledSyncService {

    private final ConnectionProfileMapper connectionProfileMapper;
    private final BankAccountMapper bankAccountMapper;
    private final BankDataSyncService syncService;

    public BankDataScheduledSyncService(ConnectionProfileMapper connectionProfileMapper,
                                        BankAccountMapper bankAccountMapper,
                                        BankDataSyncService syncService) {
        this.connectionProfileMapper = connectionProfileMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.syncService = syncService;
    }

    public void triggerScheduledSyncs() {
        Window window = yesterdayWindow();
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
            String adapterCode = adapterCodeOf(profile);
            try {
                String requestId = scheduledRequestId(profile, account, adapterCode, window);
                syncService.triggerForCompany(profile.getCompanyId(), null,
                        new BankDataSyncRequest(profile.getConnectionCode(), account.getId(), adapterCode,
                                window.start(), window.end()), requestId, "SCHEDULED");
            } catch (RuntimeException ignored) {
                // The sync service persists task failures; one company must not stop the scan.
            }
        }
    }

    /**
     * Scheduled scans follow the connection's provider (e.g. CMB) instead of a simulated adapter:
     * the generic MOCK adapter was removed with the mock-clean workstream, so routing to it made
     * every scheduled scan fail silently. A provider that is not wired now fails closed for that
     * profile only, which is the intended "真实联通才有数据" behaviour.
     */
    private String adapterCodeOf(ConnectionProfile profile) {
        String provider = profile.getProviderType();
        return provider == null || provider.isBlank() ? null : provider.trim().toUpperCase(Locale.ROOT);
    }

    private String scheduledRequestId(ConnectionProfile profile, BankAccount account, String adapterCode, Window window) {
        String key = profile.getCompanyId() + ":" + account.getId() + ":" + profile.getId()
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
