package com.finance.system.statement.voucherrule.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * W4 规则中心（2026-09-18）规则分组响应：分组 + 组内规则数。
 */
public record KingdeeRuleGroupResponse(
        Long id,
        String name,
        String description,
        Integer sortNo,
        long ruleCount) {

    /** 分组创建/更新请求体（name 必填唯一）。 */
    public record UpsertRequest(String name, String description, Integer sortNo) {
    }

    /** 规则创建/更新共用请求体（复用 Response 的 Match/LineTemplate/ExtraVoucher 结构）。 */
    public record RuleUpsertRequest(
            Integer ruleNo,
            String businessType,
            String category,
            Integer priority,
            List<String> scopeOrgs,
            List<String> scopeBankChannels,
            String direction,
            BigDecimal amountMin,
            BigDecimal amountMax,
            KingdeeVoucherRuleResponse.Match match,
            List<KingdeeVoucherRuleResponse.LineTemplate> debitLines,
            List<KingdeeVoucherRuleResponse.LineTemplate> creditLines,
            KingdeeVoucherRuleResponse.ExtraVoucher extraVoucher,
            Boolean enabled,
            String remark,
            Long groupId) {
    }

    /** Excel 导入预览行：原始单元格 + AI 映射结果（aiMapped=false 时由人工补齐）。 */
    public record ImportPreviewRow(
            int rowIndex,
            List<String> sourceCells,
            RuleUpsertRequest mapped,
            boolean aiMapped,
            Double confidence,
            String aiNote) {
    }

    /** Excel 导入预览响应：无状态两步导入的第一步（不入库）。 */
    public record ImportPreviewResponse(
            int totalRows,
            List<ImportPreviewRow> rows,
            String aiSummary) {
    }

    /** Excel 导入确认请求：人工勾选/修正后的规则行批量入库。 */
    public record ImportConfirmRequest(List<RuleUpsertRequest> rows, Long defaultGroupId) {
    }
}
