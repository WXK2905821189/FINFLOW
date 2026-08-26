package com.finance.system.bank.citic;

import com.finance.system.bank.BankTransferCommand;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Production extension point. Replace this class with an adapter backed by the
 * locally installed CITIC SDK and keep the same interface and error semantics.
 */
@Component
@ConditionalOnProperty(prefix = "bank.citic", name = "mock-mode", havingValue = "false")
public class ExternalCiticBankSdkAdapter implements CiticBankSdkClient {

    @Override
    public BigDecimal queryAvailableBalance(BankAccount account) {
        throw new BusinessException(501, "CITIC SDK adapter is not installed");
    }

    @Override
    public CiticBankSdkResult submitPayment(BankAccount payerAccount, BankTransferCommand command) {
        throw new BusinessException(501, "CITIC SDK adapter is not installed");
    }
}
