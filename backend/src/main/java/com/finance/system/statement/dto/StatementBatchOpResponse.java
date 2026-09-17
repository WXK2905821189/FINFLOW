package com.finance.system.statement.dto;

import java.util.List;

/**
 * 批量复核/推送结果：汇总计数 + 逐行明细（successCount 对 APPROVED/REJECTED/PUSHED 语义）。
 *
 * @param totalCount   请求行数
 * @param successCount 成功数（复核通过/驳回 或 推送成功）
 * @param skippedCount 跳过数（已复核过/已推送/状态不允许）
 * @param failedCount  失败数
 * @param rows         逐行结果
 */
public record StatementBatchOpResponse(
        int totalCount,
        int successCount,
        int skippedCount,
        int failedCount,
        List<StatementBatchOpRowResult> rows
) {
}
