package com.finance.system.statement.voucherrule.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条流水跑规则引擎的解析结果（WP-B）：POST /api/kingdee/voucher-rule/preview 的行响应。
 *
 * <p>status 语义（设计文档匹配算法 2.3-2.5）：</p>
 * <ul>
 *   <li>{@code AUTO_FILL} — 唯一命中，分录已按流水金额预填（含 MANUAL 行时附
 *   needManualAmount=true，推送前必须人工补金额）；</li>
 *   <li>{@code CANDIDATES} — 多条规则同时命中（如社保/个税同附言冲突对），不自动定案，
 *   全部候选返回交人工选择；</li>
 *   <li>{@code UNMATCHED} — 无规则命中（走既有 AI 制证入口兜底，未命中原因存 reason）；</li>
 *   <li>{@code NOT_ELIGIBLE} — 流水本身不可制证（非 APPROVED / 缺金额），reason 说明。</li>
 * </ul>
 */
public record KingdeeVoucherRulePreview(
        Long statementId,
        String statementNo,
        String direction,
        BigDecimal amount,
        String status,
        String reason,
        List<Candidate> candidates) {

    /** 一条命中规则的预填候选：主凭证分录 + 可选的「直接确认费用」第二张凭证。 */
    public record Candidate(
            Integer ruleNo,
            String businessType,
            String category,
            Integer priority,
            boolean needManualAmount,
            List<KingdeeVoucherEntryDraft> debitLines,
            List<KingdeeVoucherEntryDraft> creditLines,
            ExtraVoucherDraft extraVoucher) {
    }

    /** 第二张凭证草稿（规则 1、14）：同摘要，先往来凭证后费用凭证，顺序固定。 */
    public record ExtraVoucherDraft(
            String note,
            List<KingdeeVoucherEntryDraft> debitLines,
            List<KingdeeVoucherEntryDraft> creditLines) {
    }
}
