package com.finance.system.bankdata.dto;

/** 同步计划行（V25）：执行时刻与启用状态；下次执行由前端按时刻列表推算展示。 */
public record BankSyncScheduleResponse(
        Long id,
        String executeHhmm,
        boolean enabled
) {
}
