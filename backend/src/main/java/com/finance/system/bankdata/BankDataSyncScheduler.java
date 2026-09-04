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

    // CMB 账务查询规范：账务查询与支付/代发共享 20 并发，且整点/半点并发量大、响应耗时明显增加，
    // 官方建议查询错开整点半点发起。10 分钟扫描网格默认从启动后第 7 分钟开始（:07/:17/:27/...），
    // 落点天然避开 :00/:30；需要对齐银行窗口时可调 initial-delay-ms。
    @Scheduled(fixedDelayString = "${bankdata.sync.fixed-delay-ms:600000}",
            initialDelayString = "${bankdata.sync.initial-delay-ms:420000}")
    public void triggerScheduledSyncs() {
        service.triggerScheduledSyncs();
    }
}
