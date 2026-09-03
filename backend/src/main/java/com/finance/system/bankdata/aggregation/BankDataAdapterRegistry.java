package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * Explicit request wins; otherwise a connection provider may select a brand MOCK adapter.
     * When the real-call gate is open and the provider resolves to a registered REAL-mode
     * adapter (e.g. CMB -> RealCmbBankDataAdapter), the real adapter is preferred and the
     * brand MOCK adapter remains only a fallback.
     */
    public String resolveCode(String requestedCode, String providerType) {
        String requested = normalizeOrNull(requestedCode);
        if (requested != null && adapters.containsKey(requested)) return requested;
        String provider = normalizeOrNull(providerType);
        if (requested == null && provider != null) {
            if (realAdaptersEnabled) {
                BankDataAdapter providerAdapter = adapters.get(provider);
                if (providerAdapter != null && providerAdapter.executionMode() == BankAdapterExecutionMode.REAL) {
                    return provider;
                }
            }
            String brandMock = provider.endsWith("_MOCK") ? provider : provider + "_MOCK";
            if (adapters.containsKey(brandMock)) return brandMock;
            if (adapters.containsKey(provider)) return provider;
        }
        if (requested == null && adapters.containsKey("MOCK")) return "MOCK";
        throw new BusinessException(400, "Bank data adapter is not available");
    }

    public String mappingVersion(String adapterCode) {
        require(adapterCode);
        return "FINFLOW-BANKDATA-V1";
    }

    private String normalize(String value) {
        String normalized = normalizeOrNull(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
