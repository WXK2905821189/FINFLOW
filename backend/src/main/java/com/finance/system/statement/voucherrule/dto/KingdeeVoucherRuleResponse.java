package com.finance.system.statement.voucherrule.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 金蝶凭证规则的结构化视图（V34 WP-A 地基）：GET /api/kingdee/voucher-rules 的响应体，
 * 也是规则解析服务（WP-B）与 GL_VOUCHER payload 构建（WP-D）共用的 JSON 承载结构。
 *
 * <p>四个 JSON 列（match_json / debit_lines_json / credit_lines_json / extra_voucher_json）
 * 在此反序列化为强类型；未知键（match 中的 amountGate/note 等财务注释、模板行中未用字段）
 * 一律忽略——种子数据自带注释性键，禁止因注释键演进导致解析失败。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KingdeeVoucherRuleResponse(
        Long id,
        Integer ruleNo,
        String businessType,
        String category,
        Integer priority,
        List<String> scopeOrgs,
        List<String> scopeBankChannels,
        String direction,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Match match,
        List<LineTemplate> debitLines,
        List<LineTemplate> creditLines,
        ExtraVoucher extraVoucher,
        boolean enabled,
        String remark) {

    /** 匹配条件组：logic=ALL 全部满足 / ANY 任一满足；field ∈ SUMMARY|COUNTERPARTY_NAME。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Match(String logic, List<Condition> conditions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(String field, String op, List<String> values) {
    }

    /**
     * 分录模板行：dimension 为辅助维度来源（BANK_ACCOUNT/ORG/EMPLOYEE/SUPPLIER/CUSTOMER/
     * COUNTERPARTY/FIXED/BY_SUMMARY_BRANCH/NONE），share 为金额分摊策略（FULL/EQUAL/MANUAL）。
     * value 为 FIXED 维度的固定值；branches 仅供 BY_SUMMARY_BRANCH 按摘要分支选择供应商。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineTemplate(
            String account,
            String name,
            String dimension,
            String value,
            List<SummaryBranch> branches,
            String share) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummaryBranch(String whenSummaryContains, String supplier) {
    }

    /** 「直接确认费用」第二张凭证模板（规则 1、14）：同摘要，先往来凭证后费用凭证。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtraVoucher(
            String note,
            List<LineTemplate> debitLines,
            List<LineTemplate> creditLines) {
    }
}
