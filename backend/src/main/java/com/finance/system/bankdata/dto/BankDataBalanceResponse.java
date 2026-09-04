package com.finance.system.bankdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankDataBalanceResponse(
        Long id,
        Long taskId,
        Long rawMessageId,
        String contentSha256,
        LocalDateTime retentionUntil,
        Long bankAccountId,
        String accountMasked,
        String bankRequestNo,
        BigDecimal availableBalance,
        String currency,
        LocalDateTime asOfTime,
        /** 联机余额 onlblv - the account's actual funds. */
        BigDecimal onlineBalance,
        /** 冻结余额 hldblv - judicial + bank holds combined. */
        BigDecimal frozenBalance,
        /** 上日余额 accblv - online balance minus today's financial transactions. */
        BigDecimal previousDayBalance,
        /** 币种代码 ccynbr as the bank codes it, not an ISO code. */
        String vendorCurrencyCode,
        /** 分行号 bbknbr. */
        String branchCode,
        /** 银行侧账号 accnbr as the bank reports it. */
        String bankAccountNo,
        /** 银行侧户名 accnam. */
        String bankAccountName,
        /** 科目 accitm. */
        String accountItem,
        /** 客户关系号 relnbr. */
        String customerRelationNo,
        /** 账户状态 stscod: A=活动 B=冻结 C=关户. */
        String accountStatus,
        /** 开户日 opndat, 8-digit yyyyMMdd as the bank codes it. */
        String openDate,
        /** 利率类型 inttyp: ZZZ=不计息等. */
        String interestType,
        /** 存期 dpstxt. */
        String depositTerm,
        /** 透支额度 lmtovr. */
        BigDecimal overdraftLimit,
        /** 利息码 intcod: S=子公司虚拟余额. */
        String interestCode,
        /** 年利率 intrat F(11,7). */
        BigDecimal interestRate,
        /** 到期日 mutdat, 8-digit yyyyMMdd as the bank codes it. */
        String maturityDate,
        String validationStatus,
        String validationMessage,
        LocalDateTime createdAt,
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
    public BankDataBalanceResponse withLineage(String taskNo, String taskRequestId, String taskStatus) {
        return new BankDataBalanceResponse(id, taskId, rawMessageId, contentSha256, retentionUntil,
                bankAccountId, accountMasked, bankRequestNo, availableBalance, currency, asOfTime,
                onlineBalance, frozenBalance, previousDayBalance, vendorCurrencyCode, branchCode,
                bankAccountNo, bankAccountName, accountItem, customerRelationNo,
                accountStatus, openDate, interestType, depositTerm,
                overdraftLimit, interestCode, interestRate, maturityDate,
                validationStatus, validationMessage, createdAt, taskNo, taskRequestId, taskStatus);
    }
}
