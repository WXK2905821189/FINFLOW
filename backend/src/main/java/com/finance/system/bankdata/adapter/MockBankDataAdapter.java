package com.finance.system.bankdata.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "bankdata.adapter", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockBankDataAdapter implements BankDataAdapter {

    @Override
    public String adapterCode() {
        return "MOCK";
    }

    @Override
    public BankDataCollection collect(BankDataSyncContext context) {
        if (context.bankAccountId() == null) {
            return new BankDataCollection("MOCK-EMPTY-" + context.companyId(), List.of(), List.of());
        }
        String scope = context.companyId() + "-" + context.bankAccountId();
        return new BankDataCollection(
                "MOCK-REQUEST-" + scope,
                List.of(new BankDataEntry(
                        "MOCK-BANK-" + scope,
                        "MOCK-STATEMENT-" + scope + "-20260827",
                        context.bankAccountId(),
                        LocalDateTime.of(2026, 8, 27, 9, 0),
                        "INCOME",
                        new BigDecimal("100.00"),
                        "CNY",
                        "模拟付款方",
                        "MOCK-ACCOUNT-0001",
                        "模拟银行流水")),
                List.of(new BankDataBalanceEntry(
                        "MOCK-BANK-" + scope,
                        context.bankAccountId(),
                        new BigDecimal("100000.00"),
                        "CNY",
                        LocalDateTime.of(2026, 8, 27, 9, 0))));
    }
}
