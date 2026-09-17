package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 批量推送请求（「凭证草稿与制证」页：批量推送已通过复核的草稿到金蝶）。 */
public record StatementBatchPushRequest(
        @NotEmpty(message = "请选择要推送的流水") List<Long> ids
) {
}
