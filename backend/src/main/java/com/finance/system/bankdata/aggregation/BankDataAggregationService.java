package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight northbound contract and southbound adapter orchestration.
 * It contains no bank SDK, network, certificate or payment behavior.
 */
@Service
public class BankDataAggregationService {

    private final BankDataAdapterRegistry registry;
    private final BankAdapterCallExecutor callExecutor;

    /** Compatibility constructor for focused unit tests and existing internal callers. */
    public BankDataAggregationService(BankDataAdapterRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public BankDataAggregationService(BankDataAdapterRegistry registry, BankAdapterCallExecutor callExecutor) {
        this.registry = registry;
        this.callExecutor = callExecutor;
    }

    public String resolveAdapterCode(String requestedCode, String providerType) {
        return registry.resolveCode(requestedCode, providerType);
    }

    public String mappingVersion(String adapterCode) {
        return registry.mappingVersion(adapterCode);
    }

    public BankDataAggregationResult collect(BankDataSyncContext context, String adapterCode) {
        BankDataAdapter adapter = registry.require(adapterCode);
        BankAdapterCallOutcome outcome;
        if (callExecutor == null) {
            if (adapter.executionMode() != BankAdapterExecutionMode.SIMULATED) {
                return new BankDataAggregationResult(adapter.adapterCode(), mappingVersion(adapter.adapterCode()),
                        BankDataStatus.UNKNOWN, new BankDataCollection(null, List.of(), List.of()),
                        "Real bank adapter requires the managed call executor");
            }
            outcome = BankAdapterCallOutcome.response(adapter.collect(context));
        } else {
            outcome = callExecutor.invoke(adapter, context);
        }
        if (outcome.terminalStatus() != null) {
            BankDataCollection terminal = new BankDataCollection(null, List.of(), List.of(), false, null,
                    outcome.terminalStatus().name(), outcome.terminalStatus().name());
            return new BankDataAggregationResult(adapter.adapterCode(), mappingVersion(adapter.adapterCode()),
                    outcome.terminalStatus(), terminal, outcome.safeSummary());
        }
        BankDataCollection vendorResult = outcome.collection();
        if (vendorResult == null) {
            return new BankDataAggregationResult(adapter.adapterCode(), mappingVersion(adapter.adapterCode()),
                    BankDataStatus.UNKNOWN, new BankDataCollection(null, List.of(), List.of()),
                    "Adapter returned no result");
        }
        BankDataStatus status = BankDataStatus.fromVendor(
                vendorResult.status() == null || vendorResult.status().isBlank()
                        ? vendorResult.bankStatusCode() : vendorResult.status());
        List<BankDataEntry> entries = canonicalEntries(vendorResult.entries());
        List<BankDataBalanceEntry> balances = canonicalBalances(vendorResult.balances());
        boolean empty = entries.isEmpty() && balances.isEmpty();
        if (empty && status == BankDataStatus.SUCCESS) status = BankDataStatus.EMPTY;
        boolean hasMore = !empty && vendorResult.hasMore();
        String nextCursor = hasMore ? clean(vendorResult.nextCursor()) : null;
        // pageTotals is the bank's own reconciliation figure, not business vocabulary:
        // there is nothing to canonicalize and everything to lose by rebuilding it (the
        // same trap that dropped vendor fields here once before).
        BankDataCollection canonical = new BankDataCollection(vendorResult.bankRequestNo(), entries, balances,
                hasMore, nextCursor, vendorResult.bankStatusCode(), status.name(), vendorResult.pageTotals());
        return new BankDataAggregationResult(adapter.adapterCode(), mappingVersion(adapter.adapterCode()), status,
                canonical, safeSummary(status));
    }

    /**
     * Canonicalizes the accounting fields but passes {@link BankDataEntry#vendor()} through
     * untouched: the vendor block is deliberately not business vocabulary, so there is
     * nothing here to canonicalize and everything to lose by rebuilding it.
     */
    private List<BankDataEntry> canonicalEntries(List<BankDataEntry> values) {
        if (values == null) return List.of();
        return values.stream().map(entry -> entry == null ? null : new BankDataEntry(
                clean(entry.bankRequestNo()), clean(entry.statementNo()), entry.bankAccountId(), entry.transactionTime(),
                canonicalDirection(entry.direction()), entry.amount(), canonicalCurrency(entry.currency()),
                clean(entry.counterpartyName()), clean(entry.counterpartyAccount()), clean(entry.summary()),
                entry.vendor())).toList();
    }

    /** Same rule as {@link #canonicalEntries}: the four balances and the identity fields survive. */
    private List<BankDataBalanceEntry> canonicalBalances(List<BankDataBalanceEntry> values) {
        if (values == null) return List.of();
        return values.stream().map(entry -> entry == null ? null : new BankDataBalanceEntry(
                clean(entry.bankRequestNo()), entry.bankAccountId(), entry.availableBalance(),
                canonicalCurrency(entry.currency()), entry.asOfTime(),
                entry.onlineBalance(), entry.frozenBalance(), entry.previousDayBalance(),
                clean(entry.vendorCurrencyCode()), clean(entry.branchCode()), clean(entry.bankAccountNo()),
                clean(entry.bankAccountName()), clean(entry.accountItem()),
                clean(entry.customerRelationNo()))).toList();
    }

    private String canonicalDirection(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "IN", "CREDIT", "CR", "C", "RECEIPT" -> "INCOME";
            case "OUT", "DEBIT", "DR", "D", "PAYMENT" -> "EXPENSE";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private String canonicalCurrency(String value) {
        return value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeSummary(BankDataStatus status) {
        return switch (status) {
            case PENDING -> "Bank result is pending reconciliation";
            case TIMEOUT -> "Bank result timed out and requires reconciliation";
            case FAILED -> "Bank result failed before projection";
            case UNKNOWN -> "Bank result status is unknown and requires manual handling";
            case DUPLICATE -> "Bank result was marked duplicate";
            default -> null;
        };
    }
}
