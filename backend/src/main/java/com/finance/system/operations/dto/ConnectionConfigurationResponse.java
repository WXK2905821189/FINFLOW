package com.finance.system.operations.dto;

import java.util.List;

public record ConnectionConfigurationResponse(
        boolean enabled,
        String status,
        String message,
        List<String> supportedProviderTypes,
        List<ConnectionSummaryResponse> connections
) {
}
