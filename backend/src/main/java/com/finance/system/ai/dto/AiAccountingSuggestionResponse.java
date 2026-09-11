package com.finance.system.ai.dto;

/**
 * A1 智能入账建议响应。AI 只建议、不执行——所有字段仅供复核人参考/预填，
 * 是否采纳与最终入账结论仍走既有人工复核与制证流程。
 *
 * @param counterpartyType 建议对手方类型：CUSTOMER / SUPPLIER / EMPLOYEE / OTHER
 * @param confidence       0.0~1.0 的置信度自评
 */
public record AiAccountingSuggestionResponse(
        Long statementId,
        String businessCategory,
        String suggestedSummary,
        String counterpartyType,
        String settlementMethod,
        String suggestedSubject,
        String riskNotes,
        Double confidence,
        String rationale,
        String model,
        Long durationMillis) {
}
