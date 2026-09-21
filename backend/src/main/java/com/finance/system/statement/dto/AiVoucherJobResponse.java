package com.finance.system.statement.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 制证后台任务的状态与逐行结果（GET /bank-data/ai-voucher-jobs/latest，凭证中心轮询）。
 *
 * @param id           任务号
 * @param mode         DRAFT / PUSH（当前异步仅 DRAFT）
 * @param status       RUNNING / COMPLETED / FAILED
 * @param batchNo      转入批次号（多公司拆批时为首批）
 * @param totalCount   提交行数
 * @param draftCount   已生成草稿数
 * @param pushedCount  已推送数（DRAFT 任务恒为 0）
 * @param alreadyCount 幂等跳过数（此前已推送）
 * @param skippedCount 跳过数（纯人工制证账户 / 已驳回 / 已通过）
 * @param failedCount  失败数
 * @param message      任务级失败原因（status=FAILED 时非空，前端直接展示）
 * @param rows         逐行结果（含失败原因，前端渲染失败诊断）
 */
public record AiVoucherJobResponse(
        Long id,
        String mode,
        String status,
        String batchNo,
        Integer totalCount,
        Integer draftCount,
        Integer pushedCount,
        Integer alreadyCount,
        Integer skippedCount,
        Integer failedCount,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        String message,
        List<AiVoucherRowResult> rows
) {
}
