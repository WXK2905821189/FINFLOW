package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 一键推送至金蝶请求体（流水查询页「一键推送至金蝶」按钮，W16-A1）。
 *
 * <p>入参为 bank_data_statement 行主键集合；服务端跑规则中心匹配，仅唯一命中
 * （AUTO_FILL）且无需人工金额的行自动组装推送金蝶草稿，其余落为问题凭证。</p>
 */
public record PushBatchRequest(
        @NotEmpty(message = "请选择要推送的银行流水") List<Long> statementIds
) {
}
