package com.finance.system.bankdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bankdata.retention", name = "cleanup-enabled", havingValue = "true")
public class BankDataRawRetentionScheduler {

    private final BankDataRawRetentionService retentionService;

    public BankDataRawRetentionScheduler(BankDataRawRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(fixedDelayString = "${bankdata.retention.cleanup-fixed-delay-ms:3600000}")
    public void purgeExpiredPayloads() {
        retentionService.cleanupExpiredRawPayloads();
    }
}
