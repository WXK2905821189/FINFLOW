package com.finance.system.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserUpsertRequest(
        @NotBlank(message = "Username is required") @Size(min = 3, max = 64, message = "Username must be 3-64 characters") String username,
        @NotBlank(message = "Email is required") @Email(message = "Email is invalid") String email,
        String phone,
        @NotBlank(message = "Status is required") String status,
        @NotEmpty(message = "At least one role is required") List<Long> roleIds,
        String password
) {
}
