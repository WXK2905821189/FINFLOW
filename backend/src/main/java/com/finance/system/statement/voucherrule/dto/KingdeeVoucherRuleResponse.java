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
        String remark,
        Long groupId,
        String groupName) {

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
     *
     * <p>{@code dimensions}（2026-09-21 V42）为**多维度声明**：一条分录可同时带多个核算维度
     * （图虫侧规则要求「供应商 + 部门 + 业务线」）。与单维度字段并存——单维度仍走
     * dimension/value，多维度走本列表；两者都有时以本列表为准并追加。值一律由
     * {@code kingdee_dimension_mapping} 翻译成金蝶档案编码。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineTemplate(
            String account,
            String name,
            String dimension,
            String value,
            List<SummaryBranch> branches,
            String share,
            List<DimensionSpec> dimensions) {

        /** 兼容构造器：单维度模板（V34 起的旧签名与既有 seed JSON，调用点无需改动）。 */
        public LineTemplate(String account, String name, String dimension, String value,
                            List<SummaryBranch> branches, String share) {
            this(account, name, dimension, value, branches, share, null);
        }
    }

    /**
     * 多维度声明项（V42）。
     *
     * @param type   维度类型：SUPPLIER / CUSTOMER / EMPLOYEE / BANK_ACCOUNT / BUSINESS_LINE / ORG
     * @param source 来源取值方式：NAME（对手方名称）/ EMPLOYEE_NAME / SUMMARY / ACCOUNT（我方账号）/
     *               FIXED（用 value 固定值）
     * @param value  source=FIXED 时的固定值（如「部门=综合管理部」这类口径值）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DimensionSpec(String type, String source, String value) {
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
