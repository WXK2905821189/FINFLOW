package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataEntry;

import java.util.List;
import java.util.Locale;

/**
 * The one and only wire → canonical cleansing rules (direction, currency, trims).
 * Shared by the aggregation service and the raw-message replay path: if the replay
 * used a copy, a rules change would no longer be visible in a replay diff, which is
 * exactly the drift the replay exists to expose.
 */
public final class BankCanonicalizer {

    private BankCanonicalizer() {
    }

    /**
     * Canonicalizes the accounting fields but passes {@link BankDataEntry#vendor()} through
     * untouched: the vendor block is deliberately not business vocabulary, so there is
     * nothing here to canonicalize and everything to lose by rebuilding it.
     */
    public static List<BankDataEntry> canonicalEntries(List<BankDataEntry> values) {
        if (values == null) return List.of();
        return values.stream().map(entry -> entry == null ? null : new BankDataEntry(
                clean(entry.bankRequestNo()), clean(entry.statementNo()), entry.bankAccountId(), entry.transactionTime(),
                canonicalDirection(entry.direction()), entry.amount(), canonicalCurrency(entry.currency()),
                clean(entry.counterpartyName()), clean(entry.counterpartyAccount()), clean(entry.summary()),
                entry.vendor())).toList();
    }

    /** Same rule as {@link #canonicalEntries}: the four balances and the identity fields survive. */
    public static List<BankDataBalanceEntry> canonicalBalances(List<BankDataBalanceEntry> values) {
        if (values == null) return List.of();
        return values.stream().map(entry -> entry == null ? null : new BankDataBalanceEntry(
                clean(entry.bankRequestNo()), entry.bankAccountId(), entry.availableBalance(),
                canonicalCurrency(entry.currency()), entry.asOfTime(),
                entry.onlineBalance(), entry.frozenBalance(), entry.previousDayBalance(),
                clean(entry.vendorCurrencyCode()), clean(entry.branchCode()), clean(entry.bankAccountNo()),
                clean(entry.bankAccountName()), clean(entry.accountItem()),
                clean(entry.customerRelationNo()), clean(entry.accountStatus()),
                clean(entry.openDate()), clean(entry.interestType()),
                clean(entry.depositTerm()),
                entry.overdraftLimit(), clean(entry.interestCode()),
                entry.interestRate(), clean(entry.maturityDate()))).toList();
    }

    public static String canonicalDirection(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "IN", "CREDIT", "CR", "C", "RECEIPT" -> "INCOME";
            case "OUT", "DEBIT", "DR", "D", "PAYMENT" -> "EXPENSE";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    public static String canonicalCurrency(String value) {
        return value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
