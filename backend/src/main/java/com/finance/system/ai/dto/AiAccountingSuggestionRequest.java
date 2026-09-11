package com.finance.system.ai.dto;

import jakarta.validation.constraints.NotNull;

/** A1 智能入账建议请求：对指定流水行请求 AI 预填建议。 */
public record AiAccountingSuggestionRequest(
        @NotNull(message = "statementId is required") Long statementId) {
}
