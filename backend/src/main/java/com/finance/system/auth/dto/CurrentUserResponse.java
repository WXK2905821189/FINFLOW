package com.finance.system.auth.dto;

import java.util.List;

public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        String phone,
        String status,
        List<String> roles,
        List<String> permissions
) {
}
