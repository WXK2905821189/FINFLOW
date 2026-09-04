package com.finance.system.bank;

import java.math.BigDecimal;

public record BankTransferCommand(
        String requestReference,
        String payeeName,
        String payeeAccount,
        String payeeBank,
        BigDecimal amount,
        String remark
) {
}
