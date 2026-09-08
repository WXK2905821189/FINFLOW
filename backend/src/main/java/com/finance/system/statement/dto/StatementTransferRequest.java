package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 银行流水一键转入标准流水的请求体：银行数据模块 bank_data_statement 行主键集合。 */
public record StatementTransferRequest(
        @NotEmpty(message = "请选择要转入的银行流水") List<Long> statementIds
) {
}
