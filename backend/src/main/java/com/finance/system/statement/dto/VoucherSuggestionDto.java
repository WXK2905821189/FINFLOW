package com.finance.system.statement.dto;

import com.finance.system.ai.dto.VoucherEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭证草稿的结构化 AI 建议（V33 凭证草稿详情）。
 *
 * <p>双职责：statement_record.ai_suggestion_json 的存储文档（ObjectMapper 序列化/反序列化），
 * 以及 GET /statements/{id} 详情响应中的 {@code aiSuggestion} 字段。人工在凭证详情页修正后
 * 原样回写本结构（entries 替换 + edited/editedBy/editedAt 标记），推送金蝶的摘要取自
 * statement.summary（人工修正主摘要时由服务端同步回写）。</p>
 *
 * @param balanced 借贷合计是否平衡（|Σ借-Σ贷| ≤ 0.01）；不平衡不阻断保存，由页面标注人工修正
 */
public record VoucherSuggestionDto(
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
        List<VoucherEntry> entries,
        Boolean balanced,
        boolean edited,
        Long editedBy,
        LocalDateTime editedAt) {

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    public static VoucherSuggestionDto from(com.finance.system.ai.dto.AiAccountingSuggestionResponse s) {
        return new VoucherSuggestionDto(s.businessCategory(), s.suggestedSummary(), s.counterpartyType(),
                s.settlementMethod(), s.suggestedSubject(), s.riskNotes(), s.confidence(), s.rationale(),
                s.model(), s.durationMillis(), s.entries() == null ? List.of() : s.entries(),
                isBalanced(s.entries()), false, null, null);
    }

    public static Boolean isBalanced(List<VoucherEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (VoucherEntry entry : entries) {
            BigDecimal amount = entry.amount() == null ? BigDecimal.ZERO : entry.amount();
            if (VoucherEntry.DIRECTION_DEBIT.equals(entry.direction())) {
                debit = debit.add(amount);
            } else if (VoucherEntry.DIRECTION_CREDIT.equals(entry.direction())) {
                credit = credit.add(amount);
            }
        }
        return debit.subtract(credit).abs().compareTo(BALANCE_TOLERANCE) <= 0;
    }
}
