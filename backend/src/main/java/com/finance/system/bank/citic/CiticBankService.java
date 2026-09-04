package com.finance.system.bank.citic;

import com.finance.system.bank.BankService;
import com.finance.system.bank.BankTransferCommand;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.domain.entity.BankAccount;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class CiticBankService implements BankService {

    private final CiticBankSdkClient sdkClient;

    public CiticBankService(CiticBankSdkClient sdkClient) {
        this.sdkClient = sdkClient;
    }

    @Override
    public String bankCode() {
        return "CITIC";
    }

    @Override
    public BigDecimal queryAvailableBalance(BankAccount account) {
        return sdkClient.queryAvailableBalance(account);
    }

    @Override
    public BankTransferResponse submitTransfer(BankAccount payerAccount, BankTransferCommand command) {
        CiticBankSdkResult result = sdkClient.submitPayment(payerAccount, command);
        return new BankTransferResponse(null, command.requestReference(), bankCode(), result.reference(),
                result.status(), result.message());
    }
}
