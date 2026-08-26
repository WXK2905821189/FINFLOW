package com.finance.system.bank.citic;

import com.finance.system.bank.BankTransferCommand;
import com.finance.system.domain.entity.BankAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "bank.citic", name = "mock-mode", havingValue = "true", matchIfMissing = true)
public class MockCiticBankSdkClient implements CiticBankSdkClient {

    @Override
    public BigDecimal queryAvailableBalance(BankAccount account) {
        return account.getAvailableBalance();
    }

    @Override
    public CiticBankSdkResult submitPayment(BankAccount payerAccount, BankTransferCommand command) {
        return new CiticBankSdkResult(
                "CITIC-MOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(),
                "ACCEPTED",
                "Accepted by CITIC mock adapter"
        );
    }
}
