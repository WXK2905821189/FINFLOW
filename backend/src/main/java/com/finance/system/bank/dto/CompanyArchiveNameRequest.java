package com.finance.system.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create / rename a company archive. */
public record CompanyArchiveNameRequest(
        @NotBlank(message = "公司名称不能为空") @Size(max = 128, message = "公司名称过长") String name
) {
}
