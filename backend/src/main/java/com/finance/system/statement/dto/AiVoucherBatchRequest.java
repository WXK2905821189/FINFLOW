package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 一键 AI 制证请求体（银行数据模块 → 流水查询页「AI 制证」按钮）。
 *
 * <p>入参为 bank_data_statement 行主键集合；服务端按账户制证模式过滤（MANUAL 账户行跳过并回报）、
 * 转入标准流水（幂等）后按 mode 分流：</p>
 * <ul>
 *   <li>{@code PUSH}（默认）：AI 建议 → 复核闸门内化 → 推送金蝶（save→submit，audit 留给金蝶侧人工审核）；</li>
 *   <li>{@code DRAFT}（2026-09-17 决策）：AI 建议落为复核意见后<b>停在 PENDING 草稿</b>，
 *   人工在「凭证草稿与制证」页审核通过后再推送——AI 只预填不执行。</li>
 * </ul>
 */
public record AiVoucherBatchRequest(
        @NotEmpty(message = "请选择要制证的银行流水") List<Long> statementIds,
        String mode
) {
}
