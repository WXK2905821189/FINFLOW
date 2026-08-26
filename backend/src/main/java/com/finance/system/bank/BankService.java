package com.finance.system.bank;

import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.domain.entity.BankAccount;

import java.math.BigDecimal;

public interface BankService {

    String bankCode();

    BigDecimal queryAvailableBalance(BankAccount account);

    BankTransferResponse submitTransfer(BankAccount payerAccount, BankTransferCommand command);
}
