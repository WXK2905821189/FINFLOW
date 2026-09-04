package com.finance.system.feishu.dto;
import java.time.LocalDateTime;
public record NotificationDeliveryResponse(String eventId, String eventType, String referenceNo, String severity, String status, int attemptCount, String providerMessageId, String requestId, LocalDateTime createdAt, LocalDateTime sentAt, String lastError) {}
