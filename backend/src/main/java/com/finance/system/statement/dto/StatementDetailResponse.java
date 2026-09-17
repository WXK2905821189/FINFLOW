package com.finance.system.statement.dto;

import java.util.List;

/**
 * GET /statements/{id} 详情响应（V33）：新增 aiSuggestion —— 凭证草稿的结构化 AI 建议，
 * 供「凭证草稿与制证」页的金蝶式单据详情渲染（分录预填+逐行置信度+人工修正标记）。
 * 未生成过 AI 建议的历史流水为 null。
 */
public record StatementDetailResponse(
        StatementResponse statement,
        VoucherSuggestionDto aiSuggestion,
        List<StatementAuditEventResponse> auditTrail
) {
}
