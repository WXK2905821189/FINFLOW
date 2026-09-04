package com.finance.system.operations.dto;

public record DataQueryCapabilityResponse(
        String capability,
        boolean enabled,
        String status,
        String message
) {
}
