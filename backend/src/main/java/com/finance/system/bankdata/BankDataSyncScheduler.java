package com.finance.system.bankdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bankdata.sync", name = "schedule-enabled", havingValue = "true")
public class BankDataSyncScheduler {

    private final BankDataScheduledSyncService service;

    public BankDataSyncScheduler(BankDataScheduledSyncService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${bankdata.sync.fixed-delay-ms:600000}")
    public void triggerScheduledSyncs() {
        service.triggerScheduledSyncs();
    }
}
