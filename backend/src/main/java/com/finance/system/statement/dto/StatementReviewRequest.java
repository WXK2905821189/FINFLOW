package com.finance.system.statement.dto;

import jakarta.validation.constraints.NotBlank;

public record StatementReviewRequest(
        @NotBlank(message = "Review action is required") String action,
        String comment
) {
}
