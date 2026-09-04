package com.finance.system.bankdata.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Test-only deterministic fixture (mock-clean workstream 2026-09-04): production code no longer
 * ships any simulated adapter and routing is fail-closed. This class is a plain test fixture —
 * no Spring stereotype — and is never registered in a production context.
 */
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
        int page = context.pageNumber() == null || context.pageNumber() < 1 ? 1 : context.pageNumber();
        LocalDateTime from = context.windowStart() == null ? LocalDateTime.of(2026, 8, 27, 0, 0) : context.windowStart();
        LocalDateTime to = context.windowEnd() == null ? from.plusDays(1) : context.windowEnd();
        if (page > 2) {
            return new BankDataCollection(bankRequestNo(context, page), List.of(), List.of(), false, null,
                    "SUCCESS", "SUCCESS");
        }
        String scope = context.companyId() + "-" + context.bankAccountId();
        LocalDateTime baseTime = from.plusHours(page == 1 ? 9 : 10);
        return new BankDataCollection(
                bankRequestNo(context, page),
                page == 1 ? List.of(new BankDataEntry(
                        "MOCK-BANK-" + scope,
                        "MOCK-STATEMENT-" + scope + "-" + dateKey(from) + "-P1",
                        context.bankAccountId(),
                        baseTime,
                        "INCOME",
                        new BigDecimal("100.00"),
                        "CNY",
                        "模拟付款方",
                        "MOCK-ACCOUNT-0001",
                        "模拟银行流水")) : List.of(new BankDataEntry(
                        "MOCK-BANK-" + scope,
                        "MOCK-STATEMENT-" + scope + "-" + dateKey(from) + "-P2",
                        context.bankAccountId(),
                        baseTime,
                        "EXPENSE",
                        new BigDecimal("20.00"),
                        "CNY",
                        "模拟收款方",
                        "MOCK-ACCOUNT-0002",
                        "模拟分页流水")),
                page == 1 ? List.of(new BankDataBalanceEntry(
                        "MOCK-BANK-" + scope,
                        context.bankAccountId(),
                        new BigDecimal("100000.00"),
                        "CNY",
                        to.minusMinutes(1))) : List.of(),
                page == 1,
                page == 1 ? "2" : null,
                "SUCCESS",
                "SUCCESS");
    }

    private String bankRequestNo(BankDataSyncContext context, int page) {
        return "MOCK-REQUEST-" + context.companyId() + "-" + context.bankAccountId() + "-" + dateKey(context.windowStart())
                + "-P" + page;
    }

    private String dateKey(LocalDateTime value) {
        LocalDateTime date = value == null ? LocalDateTime.of(2026, 8, 27, 0, 0) : value;
        return String.format(Locale.ROOT, "%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
