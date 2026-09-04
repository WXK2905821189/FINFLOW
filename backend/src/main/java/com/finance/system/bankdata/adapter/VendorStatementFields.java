package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bank-native statement fields, stored and returned exactly as the bank reports them.
 *
 * <p>The fields below mirror CMB 招行 {@code trsQryByBreakPoint} response element
 * {@code TRANSQUERYBYBREAKPOINT_Z2} (30 fields, doc 10 of the CloudDC pack). They are kept
 * verbatim rather than translated into FINFLOW business vocabulary, because the point of this
 * module is to let a reviewer compare the screen against the bank's own statement export and
 * see the same numbers, codes and flags.</p>
 *
 * <p>Two deliberate exceptions to "verbatim":</p>
 * <ul>
 *   <li>{@code signedAmount} carries the bank's signed {@code transAmount} (D = 借方 negative,
 *       C = 贷方 positive). The legacy {@code BankDataEntry.amount} stays an unsigned magnitude
 *       because validation requires a positive accounting amount; both are stored so nothing
 *       downstream regresses.</li>
 *   <li>{@code valueDate} is parsed from the bank's {@code yyyyMMdd} into a {@link LocalDate}
 *       so it can be filtered and sorted like a date.</li>
 * </ul>
 *
 * <p>Null for adapters that do not report vendor detail (legacy/mock adapters).</p>
 */
public record VendorStatementFields(
        /** 账号 acctNbr as the bank reports it (Z1 queryAcctNbr when the row itself omits it). */
        String bankAccountNo,
        /** 起息日 valueDate. */
        LocalDate valueDate,
        /** 借贷码 loanCode: C = 贷方(收入), D = 借方(支出). */
        String loanCode,
        /** 交易类型 textCode (附录A.9). */
        String textCode,
        /** 票据号 billNumber. */
        String billNumber,
        /** 你方摘要 remarkTextClt. */
        String remarkTextClt,
        /** 冲账标志 reversalFlag: * = 冲账, X = 补账. */
        String reversalFlag,
        /** 余额 acctOnlineBal - the account balance right after this transaction. */
        BigDecimal acctOnlineBal,
        /** 带符号交易金额 transAmount: 借方为负、贷方为正（银行原始口径）。 */
        BigDecimal signedAmount,
        /** 扩展摘要 extendedRemark. */
        String extendedRemark,
        /** 收付方帐号 ctpAcctNbr - full, not masked (the bank's own counterparty reference). */
        String ctpAcctNbr,
        /** 收付方开户行行名 ctpBankName. */
        String ctpBankName,
        /** 收付方开户行地址 ctpBankAddress. */
        String ctpBankAddress,
        /** 母子公司帐号 fatOrSonAccount. */
        String fatOrSonAccount,
        /** 母子公司名称 fatOrSonCompanyName. */
        String fatOrSonCompanyName,
        /** 母子公司开户行行名 fatOrSonBankName. */
        String fatOrSonBankName,
        /** 母子公司开户行地址 fatOrSonBankAddress. */
        String fatOrSonBankAddress,
        /** 信息标志 infoFlag: 空 = 付方/子公司, 1 = 收方/子公司, 2 = 收方/母公司, 3 = 原收方/子公司. */
        String infoFlag,
        /** 业务名称 businessName. */
        String businessName,
        /** 网银业务摘要 businessText. */
        String businessText,
        /** 网银流程实例号 requestNbr. */
        String requestNbr,
        /** 网银业务参考号 yurRef. */
        String yurRef,
        /** 虚拟户编号 virtualNbr. */
        String virtualNbr,
        /** 商务支付订单号 mchOrderNbr. */
        String mchOrderNbr,
        /** 记账卡号 transCardNbr. */
        String transCardNbr,
        /** 保留字 reserve. */
        String reserve
) {
}
