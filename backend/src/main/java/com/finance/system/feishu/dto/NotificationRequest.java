package com.finance.system.feishu.dto;
import jakarta.validation.constraints.NotBlank;
public record NotificationRequest(String eventId, @NotBlank String eventType, String referenceNo, @NotBlank String severity, @NotBlank String summary, Long destinationId) {}
