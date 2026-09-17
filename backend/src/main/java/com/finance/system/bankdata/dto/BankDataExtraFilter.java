package com.finance.system.bankdata.dto;

import java.math.BigDecimal;

/**
 * WP-C 数据查询改版（2026-09-17）：Excel 式逐列筛选的服务端参数包。
 *
 * <p>与既有 keyword/status/companyId 等筛选分开打包，避免 queryProjection/export 的
 * 参数列表继续膨胀；旧签名通过 {@code none()} 保持兼容（既有测试零改动）。</p>
 *
 * @param accountNoSuffix 账号后缀（用户输入账号后 4/6 位 → bank_account_no LIKE '%suffix'）
 * @param loanCode        借贷方向（C=贷方/收，D=借方/付；仅流水）
 * @param counterparty    收付方名称模糊匹配（仅流水）
 * @param statementNo     银行流水号模糊匹配（仅流水）
 * @param minAmount       带符号金额下限（signedAmount，借方为负；仅流水）
 * @param maxAmount       带符号金额上限（仅流水）
 * @param currency        币种语义值：CNY 展开 {CNY,10,01} 全命中（银行码/ISO 并存），其余原样匹配
 */
public record BankDataExtraFilter(String accountNoSuffix, String loanCode, String counterparty,
                                  String statementNo, BigDecimal minAmount, BigDecimal maxAmount,
                                  String currency) {

    public static BankDataExtraFilter none() {
        return new BankDataExtraFilter(null, null, null, null, null, null, null);
    }

    /** trim + 空串归 null，金额字段原样透传（Controller 已做类型转换）。 */
    public BankDataExtraFilter normalize() {
        return new BankDataExtraFilter(
                blankToNull(accountNoSuffix),
                blankToNull(loanCode),
                blankToNull(counterparty),
                blankToNull(statementNo),
                minAmount, maxAmount,
                blankToNull(currency));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
