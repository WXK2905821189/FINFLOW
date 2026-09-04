package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Single routing table for controlled bank/connection adapter codes. */
@Component
public class BankDataAdapterRegistry {

    private final Map<String, BankDataAdapter> adapters;
    private final boolean realAdaptersEnabled;

    /** Compatibility constructor for focused unit tests: real-first routing stays off. */
    public BankDataAdapterRegistry(List<BankDataAdapter> adapterList) {
        this(adapterList, new BankAdapterCallProperties());
    }

    @Autowired
    public BankDataAdapterRegistry(List<BankDataAdapter> adapterList, BankAdapterCallProperties callProperties) {
        this(adapterList, callProperties.isRealAdaptersEnabled());
    }

    public BankDataAdapterRegistry(List<BankDataAdapter> adapterList, boolean realAdaptersEnabled) {
        this.adapters = adapterList.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> normalize(adapter.adapterCode()), Function.identity()));
        this.realAdaptersEnabled = realAdaptersEnabled;
    }

    public BankDataAdapter require(String adapterCode) {
        BankDataAdapter adapter = adapters.get(normalize(adapterCode));
        if (adapter == null) throw new BusinessException(400, "Bank data adapter is not available");
        return adapter;
    }

    /**
     * Fail-closed routing (review 2026-09-03, mock-clean workstream): an explicit request must be
     * a registered adapter; with no explicit request the provider type itself must be registered.
     * There is deliberately NO simulated fallback here — an unresolvable provider throws 400
     * instead of silently producing mock data. The real-call gate lives in
     * {@link BankAdapterCallExecutor} (bankdata.adapter.call.real-adapters-enabled); this
     * registry only routes.
     */
    public String resolveCode(String requestedCode, String providerType) {
        String requested = normalizeOrNull(requestedCode);
        if (requested != null) {
            require(requested);
            return requested;
        }
        String provider = normalizeOrNull(providerType);
        if (provider != null && adapters.containsKey(provider)) return provider;
        throw new BusinessException(400, "Bank data adapter is not available");
    }

    public String mappingVersion(String adapterCode) {
        require(adapterCode);
        return "FINFLOW-BANKDATA-V1";
    }

    /**
     * Adapter codes whose bean declares REAL execution mode. A real adapter is only registered
     * when its per-bank switch is on (e.g. {@code bankdata.adapter.cmb.real-enabled=true}), so
     * this set is the server-side fact source for "which banks are actually wired for real
     * traffic". Simulated/MOCK adapters are deliberately excluded.
     */
    public Set<String> realAdapterCodes() {
        return adapters.entrySet().stream()
                .filter(entry -> entry.getValue().executionMode() == BankAdapterExecutionMode.REAL)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when the given bank/provider code has a REAL-mode adapter registered. */
    public boolean isRealProvider(String providerCode) {
        String provider = normalizeOrNull(providerCode);
        return provider != null && realAdapterCodes().contains(provider);
    }

    private String normalize(String value) {
        String normalized = normalizeOrNull(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
