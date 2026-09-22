package com.finance.system.statement.dto;

import java.util.List;

/**
 * 一键推送至金蝶的批次结果（同步形态返回 / 后台任务回写共用）。
 *
 * @param batchNo      承载本次转入的批次号（已存在流水时为复用语义批次）
 * @param totalCount   请求行数
 * @param pushedCount  自动推送成功数（唯一命中且无需人工金额）
 * @param problemCount 问题凭证数（多候选/未命中/需人工金额/不可制证/推送失败）
 * @param skippedCount 跳过数（纯人工制证账户 / 无公司归属或越权 / 已人工驳回）
 * @param alreadyCount 幂等跳过数（此前已推送成功）
 * @param rows         逐行结果
 */
public record PushBatchResult(
        String batchNo,
        int totalCount,
        int pushedCount,
        int problemCount,
        int skippedCount,
        int alreadyCount,
        List<PushRowResult> rows
) {
}
