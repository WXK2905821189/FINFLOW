package com.finance.system.ai.dto;

/**
 * A1 智能入账建议响应。AI 只建议、不执行——所有字段仅供复核人参考/预填，
 * 是否采纳与最终入账结论仍走既有人工复核与制证流程。
 *
 * <p>V33（2026-09-17）扩展 {@code entries}：金蝶式分录预填（含银行存款行与业务科目行、
 * 逐行置信度），供「凭证草稿与制证」页的单据式详情渲染与人工修正。
 * 旧模型输出不含 entries 时由服务端按单科目建议降级构造。</p>
 *
 * @param counterpartyType 建议对手方类型：CUSTOMER / SUPPLIER / EMPLOYEE / OTHER
 * @param confidence       0.0~1.0 的置信度自评
 * @param entries          结构化分录预填（可能为空：AI 不可用或降级失败）
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
        Long durationMillis,
        java.util.List<VoucherEntry> entries) {
}
