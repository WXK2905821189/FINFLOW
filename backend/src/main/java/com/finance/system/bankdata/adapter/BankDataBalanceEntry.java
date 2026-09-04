package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One bank balance snapshot, carrying the vendor fields the bank actually returned.
 *
 * <p>The first five components are the projection FINFLOW used to store: a single
 * "available balance" number. That is not what the bank sends. CMB's NTQADINF
 * reports four distinct amounts - previous day, online, frozen and available - and
 * they answer different questions: how much moved today, how much is really there,
 * how much is locked, and how much can be spent.
 *
 * <p>Everything after {@code asOfTime} is optional vendor detail. It stays nullable
 * because adapters other than CMB may not report it, and because rows captured
 * before the field extension genuinely have no value for it - none is invented.
 */
public record BankDataBalanceEntry(
        String bankRequestNo,
        Long bankAccountId,
        BigDecimal availableBalance,
        String currency,
        LocalDateTime asOfTime,
        /** 联机余额 onlblv: the account's actual funds. */
        BigDecimal onlineBalance,
        /** 冻结余额 hldblv: judicial + bank holds combined. */
        BigDecimal frozenBalance,
        /** 上日余额 accblv: online balance minus today's financial transactions. */
        BigDecimal previousDayBalance,
        /** 币种代码 ccynbr, as the bank codes it (10 = CNY) rather than an ISO code. */
        String vendorCurrencyCode,
        /** 分行号 bbknbr. */
        String branchCode,
        /** 银行侧账号 accnbr. */
        String bankAccountNo,
        /** 银行侧户名 accnam. */
        String bankAccountName,
        /** 科目 accitm. */
        String accountItem,
        /** 客户关系号 relnbr. */
        String customerRelationNo
) {

    /** Projection-only constructor kept for adapters that report a single available amount. */
    public BankDataBalanceEntry(String bankRequestNo, Long bankAccountId, BigDecimal availableBalance,
                                String currency, LocalDateTime asOfTime) {
        this(bankRequestNo, bankAccountId, availableBalance, currency, asOfTime,
                null, null, null, null, null, null, null, null, null);
    }
}
