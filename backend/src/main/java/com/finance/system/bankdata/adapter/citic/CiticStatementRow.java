package com.finance.system.bankdata.adapter.citic;

import java.math.BigDecimal;

/**
 * DLTRNALL response row. Field names mirror the vendor XML exactly (including
 * the vendor's original {@code abstract} tag, mapped to {@code summary} here).
 *
 * @param tranDate          transaction date YYYYMMDD
 * @param tranTime          transaction time hhmmss
 * @param tranNo            teller transaction number (one statement identity key)
 * @param sumTranNo         total transaction serial number (identity key)
 * @param tranAmount        transaction amount
 * @param creditDebitFlag   D = debit/expense, C = credit/income
 * @param oppAccountNo      counterparty account number
 * @param oppAccountName    counterparty account name
 * @param oppOpenBankName   counterparty bank
 * @param summary           memo / abstract
 * @param balance           account balance after the transaction
 * @param oriNum            original serial number (controlFlag >= 2, idempotency key)
 */
public record CiticStatementRow(
        String tranDate,
        String tranTime,
        String tranNo,
        String sumTranNo,
        BigDecimal tranAmount,
        String creditDebitFlag,
        String oppAccountNo,
        String oppAccountName,
        String oppOpenBankName,
        String summary,
        BigDecimal balance,
        String oriNum
) {
}
