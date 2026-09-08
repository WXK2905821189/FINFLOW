package com.finance.system.bank.dto;

import jakarta.validation.constraints.NotNull;

/** Re-file a bank account into a company archive. */
public record AccountCompanyAssignRequest(
        @NotNull(message = "目标公司档案不能为空") Long companyId
) {
}
