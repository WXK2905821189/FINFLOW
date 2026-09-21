package com.finance.system.bank.dto;

import java.math.BigDecimal;

/**
 * Bank account projection.
 *
 * <p>{@code status} is the account's own lifecycle status (ACTIVE/DISABLED/...). The
 * {@code directStatus}/{@code lastRealSyncAt} pair is the server-side answer to "is this single
 * account really direct-connected", resolved per row by {@code AccountDirectStatusService}
 * (REAL adapter assembled + at least one successful real sync for this account). It is never
 * derived from a global/other-bank connection flag.
 */
public record BankAccountResponse(
        Long id,
        String bankCode,
        String accountName,
        String maskedAccountNumber,
        String currency,
        BigDecimal availableBalance,
        String status,
        /** DIRECT_CONNECTED | ONBOARDED | NOT_CONNECTED */
        String directStatus,
        /** ISO timestamp of the latest successful real-adapter sync for this account, nullable. */
        String lastRealSyncAt,
        /** 账户归属公司 id：跨公司用户用于「公司主体 → 账户」两级分组筛选。 */
        Long companyId,
        /** 账户归属公司名称；与 companyId 同源，仅展示用。 */
        String companyName,
        /** 制证模式（V31）：KINGDEE_AUTO | MANUAL（纯人工制证，不进 AI 制证推送链路）。 */
        String accountingMode,
        /**
         * 金蝶银行账号档案编码（V41，CN_BANKACNT.FNumber）：总账凭证「银行账号」核算维度值。
         * null = 未映射（KINGDEE_AUTO 账户制证时会被阻断并提示补映射）。
         */
        String kingdeeAccountNumber
) {
}
