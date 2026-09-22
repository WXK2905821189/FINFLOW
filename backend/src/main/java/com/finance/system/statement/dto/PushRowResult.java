package com.finance.system.statement.dto;

/**
 * 一键推送至金蝶的逐行结果（W16-A1）。
 *
 * @param bankDataStatementId 银行流水行主键（bank_data_statement.id）
 * @param statementNo         流水号（转入标准流水后的自然键）
 * @param outcome             PUSHED（本次自动推送成功）
 *                            | PROBLEM_CANDIDATES（多规则命中，需人工选规则）
 *                            | PROBLEM_UNMATCHED（无规则命中）
 *                            | PROBLEM_MANUAL_AMOUNT（唯一命中但含 MANUAL 分摊行，需人工补金额）
 *                            | PROBLEM_ELIGIBLE（不可制证：非 APPROVED/缺金额/校验未通过）
 *                            | PROBLEM_PUSH_FAILED（规则命中但金蝶推送失败）
 *                            | ALREADY_PUSHED（此前已推送成功，幂等跳过——终局不动）
 *                            | SKIPPED_MANUAL（账户为纯人工制证模式，仅留系统）
 *                            | SKIPPED（无公司归属 / 越权 / 已人工驳回）
 * @param ruleNo              命中的规则号（仅 PUSHED / PROBLEM_CANDIDATES / PROBLEM_MANUAL_AMOUNT 可能非空）
 * @param voucherNo           金蝶凭证号（推送成功后）
 * @param pushStatus          推送状态（NOT_PUSHED/GL_PUSHED/GL_FAILED/...）
 * @param message             逐行说明（问题原因 / 失败原因 / 提示）
 */
public record PushRowResult(
        Long bankDataStatementId,
        String statementNo,
        String outcome,
        Integer ruleNo,
        String voucherNo,
        String pushStatus,
        String message
) {

    /** 是否问题凭证（进凭证中心人工处理；推送成功与纯跳过不算）。 */
    public static boolean isProblem(String outcome) {
        return outcome != null && outcome.startsWith("PROBLEM_");
    }
}
