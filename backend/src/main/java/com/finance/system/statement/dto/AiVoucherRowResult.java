package com.finance.system.statement.dto;

/**
 * 一键 AI 制证的逐行结果。
 *
 * @param bankDataStatementId 银行流水行主键（bank_data_statement.id）
 * @param statementNo         流水号（转入标准流水后的自然键）
 * @param outcome             PUSHED（本次已推送）| DRAFT_CREATED（已生成 PENDING 草稿，待人工复核推送）
 *                            | ALREADY_APPROVED（此前已通过复核，可直接推送）
 *                            | ALREADY_PUSHED（此前已推送，幂等跳过）
 *                            | SKIPPED_MANUAL（账户为纯人工制证模式，仅留库）
 *                            | SKIPPED_REJECTED（此前已被人工驳回，尊重驳回结果）
 *                            | FAILED_VALIDATION（转入校验未通过）| FAILED（推送或建议链路失败）
 * @param aiStatus            OK | UNAVAILABLE（AI 未配置/无权限/限频，已按无建议继续推送）
 * @param aiBusinessCategory  AI 建议业务类别
 * @param aiSuggestedSummary  AI 建议入账摘要
 * @param aiSuggestedSubject  AI 建议会计科目
 * @param aiConfidence        AI 建议置信度（0-1）
 * @param voucherNo           金蝶单据编号（推送成功后）
 * @param pushStatus          推送状态（NOT_PUSHED/PROCESSING/PUSHED/FAILED）
 * @param message             逐行说明（失败原因 / AI 降级原因等）
 */
public record AiVoucherRowResult(
        Long bankDataStatementId,
        String statementNo,
        String outcome,
        String aiStatus,
        String aiBusinessCategory,
        String aiSuggestedSummary,
        String aiSuggestedSubject,
        Double aiConfidence,
        String voucherNo,
        String pushStatus,
        String message
) {
}
