package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量复核请求（「凭证草稿与制证」页：批量通过 / 批量驳回草稿）。
 *
 * <p>action 仅支持 APPROVE | REJECT；REJECT 必须带 comment。BANKDATA 批次（AI 草稿链路）
 * 允许生成人自审；其余批次沿用「导入者不能复核自己」的职责分离规则。</p>
 */
public record StatementBatchOpRequest(
        @NotEmpty(message = "请选择要复核的流水") List<Long> ids,
        String action,
        String comment
) {
}
