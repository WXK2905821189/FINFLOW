package com.finance.system.ai.dto;

import java.time.format.DateTimeFormatter;

/** AI 调用日志行（审计查看）：哈希+摘要口径，不含完整上下文。 */
public record AiCallLogResponse(Long id, String capability, Long userId, Long companyId,
                                String provider, String model, String status,
                                String promptHash, String promptSummary,
                                String responseHash, String responseSummary,
                                String errorMessage, Long durationMs,
                                Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                String createdAt) {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static AiCallLogResponse from(com.finance.system.domain.entity.AiCallLog log) {
        return new AiCallLogResponse(log.getId(), log.getCapability(), log.getUserId(), log.getCompanyId(),
                log.getProvider(), log.getModel(), log.getStatus(),
                log.getPromptHash(), log.getPromptSummary(),
                log.getResponseHash(), log.getResponseSummary(),
                log.getErrorMessage(), log.getDurationMs(),
                log.getPromptTokens(), log.getCompletionTokens(), log.getTotalTokens(),
                log.getCreatedAt() == null ? null : log.getCreatedAt().format(DATETIME));
    }
}
