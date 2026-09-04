package com.finance.system.audit.dto;
import java.time.LocalDateTime;
public record SystemAuditEventResponse(Long id, Long actorId, String action, String objectType, String objectId,
                                       String requestId, String result, String detail, LocalDateTime createdAt) {}
