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
        List<BankDataEntry> entries = BankCanonicalizer.canonicalEntries(vendorResult.entries());
        List<BankDataBalanceEntry> balances = BankCanonicalizer.canonicalBalances(vendorResult.balances());
        boolean empty = entries.isEmpty() && balances.isEmpty();
        if (empty && status == BankDataStatus.SUCCESS) status = BankDataStatus.EMPTY;
        boolean hasMore = !empty && vendorResult.hasMore();
        String nextCursor = hasMore ? clean(vendorResult.nextCursor()) : null;
        // pageTotals is the bank's own reconciliation figure, not business vocabulary:
        // there is nothing to canonicalize and everything to lose by rebuilding it (the
        // same trap that dropped vendor fields here once before). evidence is wire data,
        // not business vocabulary either — pass it through untouched for the ODS layer.
        BankDataCollection canonical = new BankDataCollection(vendorResult.bankRequestNo(), entries, balances,
                hasMore, nextCursor, vendorResult.bankStatusCode(), status.name(), vendorResult.pageTotals(),
                vendorResult.evidence());
        return new BankDataAggregationResult(adapter.adapterCode(), mappingVersion(adapter.adapterCode()), status,
                canonical, safeSummary(status));
    }

    private String clean(String value) {
        return BankCanonicalizer.clean(value);
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
