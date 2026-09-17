package com.finance.system.statement.dto;

/**
 * 批量复核/推送的逐行结果。
 *
 * <p>batch-review 的 outcome：APPROVED | REJECTED | SKIPPED（已复核过/校验未通过等）| FAILED；<br>
 * batch-push 的 outcome：PUSHED | ALREADY_PUSHED | SKIPPED（未通过复核/校验等）| FAILED。</p>
 *
 * @param id          statement_record.id
 * @param statementNo 流水号
 * @param outcome     逐行结果（见上）
 * @param voucherNo   金蝶单据编号（推送成功时）
 * @param message     逐行说明（失败原因 / 跳过原因）
 */
public record StatementBatchOpRowResult(
        Long id,
        String statementNo,
        String outcome,
        String voucherNo,
        String message
) {
}
