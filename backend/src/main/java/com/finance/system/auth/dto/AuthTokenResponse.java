package com.finance.system.auth.dto;

public record AuthTokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        CurrentUserResponse user
) {
}
