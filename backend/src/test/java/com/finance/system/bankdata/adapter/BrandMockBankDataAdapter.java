package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Shared deterministic test fixture for branded MOCK adapters; never performs I/O, never registered in production. */
abstract class BrandMockBankDataAdapter implements BankDataAdapter {

    private final String code;
    private final String brand;
    private final String incomeDirection;
    private final String expenseDirection;

    BrandMockBankDataAdapter(String code, String brand, String incomeDirection, String expenseDirection) {
        this.code = code;
        this.brand = brand;
        this.incomeDirection = incomeDirection;
        this.expenseDirection = expenseDirection;
    }

    @Override
    public String adapterCode() {
        return code;
    }

    @Override
    public BankDataCollection collect(BankDataSyncContext context) {
        int page = context.pageNumber() == null || context.pageNumber() < 1 ? 1 : context.pageNumber();
        LocalDateTime from = context.windowStart() == null
                ? LocalDateTime.of(2026, 8, 27, 0, 0) : context.windowStart();
        LocalDateTime to = context.windowEnd() == null ? from.plusDays(1) : context.windowEnd();
        String scope = context.companyId() + "-" + context.bankAccountId();
        String day = String.format(Locale.ROOT, "%04d%02d%02d", from.getYear(), from.getMonthValue(), from.getDayOfMonth());
        if (page > 2) return new BankDataCollection(request(context, day, page), List.of(), List.of(), false,
                null, "AAAAAAA", "SUCCESS");
        BankDataEntry entry = new BankDataEntry(request(context, day, page), brand + "-STMT-" + scope + "-" + day + "-" + page,
                context.bankAccountId(), from.plusHours(page + 7), page == 1 ? incomeDirection : expenseDirection,
                page == 1 ? new BigDecimal("88.00") : new BigDecimal("12.00"), "cny", brand + " 模拟对手方",
                brand + "-ACCOUNT-0001", brand + " mock statement");
        BankDataBalanceEntry balance = page == 1 ? new BankDataBalanceEntry(request(context, day, page),
                context.bankAccountId(), new BigDecimal("1000.00"), "CNY", to.minusMinutes(1)) : null;
        return new BankDataCollection(request(context, day, page), List.of(entry),
                balance == null ? List.of() : List.of(balance), page == 1, page == 1 ? "2" : null,
                "AAAAAAA", "SUCCESS");
    }

    private String request(BankDataSyncContext context, String day, int page) {
        return code + "-" + context.companyId() + "-" + context.bankAccountId() + "-" + day + "-P" + page;
    }
}
