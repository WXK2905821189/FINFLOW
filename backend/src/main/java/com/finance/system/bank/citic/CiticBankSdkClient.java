package com.finance.system.bank.citic;

import com.finance.system.bank.BankTransferCommand;
import com.finance.system.domain.entity.BankAccount;

import java.math.BigDecimal;

/**
 * Boundary for the vendor-supplied CITIC SDK. Replace its implementation
 * without changing the business-facing BankService contract.
 */
public interface CiticBankSdkClient {

    BigDecimal queryAvailableBalance(BankAccount account);

    CiticBankSdkResult submitPayment(BankAccount payerAccount, BankTransferCommand command);
}
