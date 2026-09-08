package com.finance.system.bankdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳调度器（V25 / 方案 D1=A1，2026-09-08）：
 * 旧版 fixedDelay 10 分钟网格每天产生 ~144 次幂等探测且首轮真实拉取时间不可控；
 * 新版每分钟扫描一次 {@code bank_sync_schedule}（V25），命中管理员配置的时刻
 * （默认种子 02:10，银行低谷期）才触发一轮全账户 T-1 同步。幂等 requestId 保证
 * 同窗口重复触发安全；错峰护栏（禁整点/半点）在计划 API 层强制。
 */
@Component
@ConditionalOnProperty(prefix = "bankdata.sync", name = "schedule-enabled", havingValue = "true")
public class BankDataSyncScheduler {

    private final BankSyncScheduleService scheduleService;

    public BankDataSyncScheduler(BankSyncScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(fixedDelayString = "${bankdata.sync.heartbeat-delay-ms:60000}",
            initialDelayString = "${bankdata.sync.heartbeat-initial-delay-ms:30000}")
    public void heartbeat() {
        scheduleService.fireIfDue();
    }
}
