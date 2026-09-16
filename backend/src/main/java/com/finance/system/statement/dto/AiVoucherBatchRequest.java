package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 一键 AI 制证请求体（银行数据模块 → 流水查询页「AI 制证推送」按钮）。
 *
 * <p>入参为 bank_data_statement 行主键集合；服务端按账户制证模式过滤（MANUAL 账户行跳过并回报）、
 * 转入标准流水（幂等）、生成 AI 入账建议、复核闸门内化（金蝶侧人工审核）后推送金蝶。</p>
 */
public record AiVoucherBatchRequest(
        @NotEmpty(message = "请选择要制证的银行流水") List<Long> statementIds
) {
}
