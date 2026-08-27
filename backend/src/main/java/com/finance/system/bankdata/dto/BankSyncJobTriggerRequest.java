package com.finance.system.bankdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BankSyncJobTriggerRequest(
        @NotBlank(message = "Job type is required")
        @Size(max = 64, message = "Job type must be at most 64 characters") String jobType,
        @NotNull(message = "Bank account id is required") Long bankAccountId,
        @Size(max = 64, message = "Connection code must be at most 64 characters") String connectionCode,
        @Size(max = 64, message = "Window start must be at most 64 characters") String windowStart,
        @Size(max = 64, message = "Window end must be at most 64 characters") String windowEnd
) {
}
