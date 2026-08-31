package com.finance.system.closing.dto;
import java.time.LocalDateTime;
public record ClosingPeriodResponse(Long id, String period, String status, int totalCount, int pendingCount,
                                    int exceptionCount, int unpostedCount, Long confirmedBy, LocalDateTime confirmedAt,
                                    String requestId, String note, LocalDateTime updatedAt) {}
