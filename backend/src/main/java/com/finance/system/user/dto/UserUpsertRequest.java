package com.finance.system.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserUpsertRequest(
        // W9：min=3 拿掉——中文姓名两个字符是正常场景，非空（NotBlank）+ 上限 64 即可。
        @NotBlank(message = "Username is required") @Size(max = 64, message = "Username must be at most 64 characters") String username,
        @NotBlank(message = "Email is required") @Email(message = "Email is invalid") String email,
        String phone,
        @NotBlank(message = "Status is required") String status,
        @NotEmpty(message = "At least one role is required") List<Long> roleIds,
        String password
) {
}
