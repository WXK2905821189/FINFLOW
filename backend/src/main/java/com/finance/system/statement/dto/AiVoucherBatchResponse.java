package com.finance.system.statement.dto;

import java.util.List;

/**
 * 一键 AI 制证批次结果：汇总计数 + 逐行明细。
 *
 * @param batchNo       承载本次转入的批次号（已存在流水时为复用语义批次）
 * @param totalCount    请求行数
 * @param draftCount    本次生成草稿数（DRAFT 模式；PENDING 落「凭证草稿与制证」页待人工复核）
 * @param pushedCount   本次推送成功数（PUSH 模式）
 * @param alreadyCount  幂等跳过（此前已推送）数
 * @param skippedCount  跳过数（纯人工制证账户 / 人工驳回 / 已通过复核可直接推送）
 * @param failedCount   失败数（校验/推送）
 * @param rows          逐行结果
 */
public record AiVoucherBatchResponse(
        String batchNo,
        int totalCount,
        int draftCount,
        int pushedCount,
        int alreadyCount,
        int skippedCount,
        int failedCount,
        List<AiVoucherRowResult> rows
) {
}
