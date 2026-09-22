package com.finance.system.statement.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一键推送后台任务的状态与逐行结果（凭证中心轮询；latest 按公司取最近一个任务）。
 *
 * @param id           任务号
 * @param status       RUNNING / COMPLETED / FAILED
 * @param batchNo      转入批次号（多公司拆批时为首批）
 * @param totalCount   提交行数
 * @param pushedCount  自动推送成功数
 * @param problemCount 问题凭证数
 * @param skippedCount 跳过数
 * @param alreadyCount 幂等跳过数（此前已推送成功）
 * @param createdAt    提交时间
 * @param finishedAt   完成时间
 * @param message      任务级失败原因（status=FAILED 时非空，前端直接展示）
 * @param rows         逐行结果（含失败原因，前端渲染诊断）
 */
public record PushJobResponse(
        Long id,
        String status,
        String batchNo,
        Integer totalCount,
        Integer pushedCount,
        Integer problemCount,
        Integer skippedCount,
        Integer alreadyCount,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        String message,
        List<PushRowResult> rows
) {
}
