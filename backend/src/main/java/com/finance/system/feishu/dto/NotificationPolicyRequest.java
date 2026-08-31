package com.finance.system.feishu.dto;
import jakarta.validation.constraints.NotBlank;
public record NotificationPolicyRequest(@NotBlank String eventType, Long destinationId, boolean enabled) {}
