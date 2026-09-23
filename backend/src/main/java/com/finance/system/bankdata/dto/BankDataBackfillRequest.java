package com.finance.system.bankdata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * W17 包 F：POST /api/bank-data/backfill 请求体。
 *
 * @param accountId 目标账户；缺省 = 本公司全部 ACTIVE 账户
 * @param historyStart 历史起点（含），如 2024-09-23
 * @param historyEnd 历史终点（含）；缺省 = 昨日
 * @param statements 是否回补流水（≤90 天分片，走既有同步管道）
 * @param balances 是否回补历史余额（≤30 天切片，按日快照）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankDataBackfillRequest(
        Long accountId,
        @NotNull(message = "historyStart is required") LocalDate historyStart,
        LocalDate historyEnd,
        Boolean statements,
        Boolean balances
) {

    /** 缺省 = true（不传即回补流水）。 */
    public boolean wantStatements() {
        return statements == null || statements;
    }

    /** 缺省 = true（不传即回补历史余额）。 */
    public boolean wantBalances() {
        return balances == null || balances;
    }
}
