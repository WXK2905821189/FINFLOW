package com.finance.system.bankdata.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建同步计划：仅执行时刻（HH:mm，禁整点/半点）；启用状态默认 true。 */
public record BankSyncScheduleCreateRequest(
        @NotBlank(message = "执行时刻不能为空") String executeHhmm
) {
}
