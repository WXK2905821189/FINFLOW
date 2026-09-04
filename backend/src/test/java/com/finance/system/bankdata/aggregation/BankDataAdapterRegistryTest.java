package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-closed routing contract (mock-clean workstream 2026-09-04): an explicit request must be a
 * registered adapter, and a provider resolves only to itself — there is deliberately no brand-MOCK
 * or generic-MOCK fallback in production. Unresolvable codes fail with 400 instead of silently
 * producing simulated data.
 */
class BankDataAdapterRegistryTest {

    private final BankDataAdapter realCmb = new ModeAdapter("CMB", BankAdapterExecutionMode.REAL);
    private final BankDataAdapter mockCmb = new ModeAdapter("CMB_MOCK", BankAdapterExecutionMode.SIMULATED);
    private final BankDataAdapter genericMock = new ModeAdapter("MOCK", BankAdapterExecutionMode.SIMULATED);

    @Test
    void explicitRegisteredCodeWinsAndProviderResolvesToItself() {
        BankDataAdapterRegistry registry = registry(true, realCmb, mockCmb, genericMock);

        assertEquals("CMB", registry.resolveCode(null, "CMB"));
        assertEquals("CMB", registry.resolveCode("CMB", "CMB"));
        assertEquals("CMB_MOCK", registry.resolveCode("CMB_MOCK", "CMB"));
        assertEquals("MOCK", registry.resolveCode("MOCK", null));
    }

    @Test
    void providerResolvesOnlyWhenTheProviderCodeItselfIsRegistered() {
        BankDataAdapterRegistry registry = registry(true, realCmb, mockCmb, genericMock);
        assertEquals("CMB", registry.resolveCode(null, "CMB"));

        BankDataAdapterRegistry noRealAdapter = registry(true, mockCmb, genericMock);
        assertEquals("CMB_MOCK", noRealAdapter.resolveCode(null, "CMB_MOCK"));
    }

    @Test
    void unresolvableProviderFailsClosedInsteadOfBecomingMock() {
        // No brand-MOCK or generic-MOCK fallback: a provider resolves only to a registered adapter,
        // so an unregistered bank is rejected outright.
        BankDataAdapterRegistry withoutCmb = registry(false, mockCmb, genericMock);
        assertThrows(BusinessException.class, () -> withoutCmb.resolveCode(null, "CMB"));

        BankDataAdapterRegistry onlyGeneric = registry(false, genericMock);
        assertThrows(BusinessException.class, () -> onlyGeneric.resolveCode(null, "CITIC"));
        assertThrows(BusinessException.class, () -> onlyGeneric.resolveCode(null, null));

        BankDataAdapterRegistry wired = registry(true, realCmb, mockCmb, genericMock);
        assertThrows(BusinessException.class, () -> wired.resolveCode(null, "UNKNOWN_BANK"));
    }

    @Test
    void registeredProviderResolvesEvenWhenTheRealCallGateIsClosed() {
        // Routing is not the gate: whether a real call may be issued is decided by
        // BankAdapterCallExecutor (bankdata.adapter.call.real-adapters-enabled). A registered
        // adapter stays routable so the executor can produce an explicit refusal instead of a
        // confusing "adapter not available".
        BankDataAdapterRegistry closedGate = registry(false, realCmb, mockCmb, genericMock);
        assertEquals("CMB", closedGate.resolveCode(null, "CMB"));
    }

    @Test
    void explicitUnknownCodeIsRejected() {
        BankDataAdapterRegistry registry = registry(true, realCmb, mockCmb, genericMock);

        assertThrows(BusinessException.class, () -> registry.resolveCode("REAL_NOT_REGISTERED", "CMB"));
        assertThrows(BusinessException.class, () -> registry.require("REAL_NOT_REGISTERED"));
    }

    private BankDataAdapterRegistry registry(boolean realAdaptersEnabled, BankDataAdapter... adapters) {
        return new BankDataAdapterRegistry(List.of(adapters), realAdaptersEnabled);
    }

    private static final class ModeAdapter implements BankDataAdapter {
        private final String code;
        private final BankAdapterExecutionMode mode;

        private ModeAdapter(String code, BankAdapterExecutionMode mode) {
            this.code = code;
            this.mode = mode;
        }

        @Override
        public String adapterCode() {
            return code;
        }

        @Override
        public BankAdapterExecutionMode executionMode() {
            return mode;
        }

        @Override
        public BankDataCollection collect(BankDataSyncContext context) {
            return new BankDataCollection(code + "-REQ", List.of(), List.of());
        }
    }
}
