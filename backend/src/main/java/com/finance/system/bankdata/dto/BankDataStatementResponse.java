package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One bank statement row as returned to the client.
 *
 * <p>The fields from {@code valueDate} onwards are the bank's own
 * (CMB {@code trsQryByBreakPoint} {@code TRANSQUERYBYBREAKPOINT_Z2}) and are passed through
 * verbatim — same names, same codes, same sign convention — so the screen can be laid next to
 * the bank's statement export and compared field by field.</p>
 *
 * <p>Conventions worth knowing:</p>
 * <ul>
 *   <li>{@code amount} is the unsigned accounting magnitude; {@code signedAmount} is the bank's
 *       {@code transAmount} with its sign (D 借方 negative, C 贷方 positive).</li>
 *   <li>{@code direction} is FINFLOW's INCOME/EXPENSE derived from {@code loanCode};
 *       {@code loanCode} itself is the bank's C/D.</li>
 *   <li>{@code counterpartyAccountMasked} is our masked view of the counterparty account,
 *       {@code ctpAcctNbr} is the full value the bank returned.</li>
 * </ul>
 */
public record BankDataStatementResponse(
        Long id,
        Long taskId,
        Long rawMessageId,
        String contentSha256,
        LocalDateTime retentionUntil,
        Long bankAccountId,
        String bankRequestNo,
        String statementNo,
        LocalDateTime transactionTime,
        String direction,
        BigDecimal amount,
        String currency,
        String counterpartyName,
        String counterpartyAccountMasked,
        String summary,
        String validationStatus,
        String validationMessage,
        LocalDateTime createdAt,
        /** 本方脱敏账号：仅投影查询填充，其它端点为 null。 */
        String accountMasked,
        String bankAccountNo,
        LocalDate valueDate,
        String loanCode,
        BigDecimal signedAmount,
        String textCode,
        String billNumber,
        String remarkTextClt,
        String reversalFlag,
        BigDecimal acctOnlineBal,
        String extendedRemark,
        String ctpAcctNbr,
        String ctpBankName,
        String ctpBankAddress,
        String fatOrSonAccount,
        String fatOrSonCompanyName,
        String fatOrSonBankName,
        String fatOrSonBankAddress,
        String infoFlag,
        String businessName,
        String businessText,
        String requestNbr,
        String yurRef,
        String virtualNbr,
        String mchOrderNbr,
        String transCardNbr,
        String reserve,
        /** 产出该行的同步任务号；仅投影查询填充。 */
        String taskNo,
        /** 产出该行的同步任务请求编号；仅投影查询填充。 */
        String taskRequestId,
        /** 产出该行的同步任务状态（SUCCEEDED / UNKNOWN / ...）；仅投影查询填充。 */
        String taskStatus
) {

    /**
     * Attaches the producing sync task's lineage. Only the projection query fills these:
     * they tell the reviewer which bank call produced the row and whether that call resolved.
     */
    public BankDataStatementResponse withLineage(String taskNo, String taskRequestId, String taskStatus,
                                                 String accountMasked) {
        return new BankDataStatementResponse(id, taskId, rawMessageId, contentSha256, retentionUntil,
                bankAccountId, bankRequestNo, statementNo, transactionTime, direction, amount, currency,
                counterpartyName, counterpartyAccountMasked, summary, validationStatus, validationMessage,
                createdAt, accountMasked, bankAccountNo, valueDate, loanCode, signedAmount, textCode,
                billNumber, remarkTextClt, reversalFlag, acctOnlineBal, extendedRemark, ctpAcctNbr,
                ctpBankName, ctpBankAddress, fatOrSonAccount, fatOrSonCompanyName, fatOrSonBankName,
                fatOrSonBankAddress, infoFlag, businessName, businessText, requestNbr, yurRef, virtualNbr,
                mchOrderNbr, transCardNbr, reserve, taskNo, taskRequestId, taskStatus);
    }
}
