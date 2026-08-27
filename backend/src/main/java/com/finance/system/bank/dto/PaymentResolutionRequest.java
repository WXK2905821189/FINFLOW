package com.finance.system.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentResolutionRequest(
        @NotBlank(message = "Resolution action is required") String action,
        @Size(max = 128, message = "External reference must be at most 128 characters") String externalReference,
        @NotBlank(message = "Resolution comment is required")
        @Size(max = 500, message = "Resolution comment must be at most 500 characters") String comment
) {
}
