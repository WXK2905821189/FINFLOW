package com.finance.system.bankdata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BankDataSyncRequest(
        @Size(max = 64, message = "Connection code must be at most 64 characters") String connectionCode,
        @NotNull(message = "Bank account id is required") Long bankAccountId,
        @Size(max = 64, message = "Adapter code must be at most 64 characters") String adapterCode
) {
}
