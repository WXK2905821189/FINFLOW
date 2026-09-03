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
 * Runtime evidence for the CMB gap ticket: when the real-call gate is open and the
 * provider resolves to a REAL-mode adapter, that adapter wins over the brand MOCK;
 * mock remains only a fallback and an explicit unknown code never silently becomes MOCK.
 */
class BankDataAdapterRegistryTest {

    private final BankDataAdapter realCmb = new ModeAdapter("CMB", BankAdapterExecutionMode.REAL);
    private final BankDataAdapter mockCmb = new ModeAdapter("CMB_MOCK", BankAdapterExecutionMode.SIMULATED);
    private final BankDataAdapter genericMock = new ModeAdapter("MOCK", BankAdapterExecutionMode.SIMULATED);

    @Test
    void realAdapterIsPreferredOverBrandMockWhenRealGateIsOpen() {
        BankDataAdapterRegistry registry = registry(true, realCmb, mockCmb, genericMock);

        assertEquals("CMB", registry.resolveCode(null, "CMB"));
        // An explicit request keeps working either way.
        assertEquals("CMB", registry.resolveCode("CMB", "CMB"));
        assertEquals("CMB_MOCK", registry.resolveCode("CMB_MOCK", "CMB"));
    }

    @Test
    void brandMockIsTheFallbackWhenRealGateIsClosedOrNoRealAdapterIsRegistered() {
        BankDataAdapterRegistry closedGate = registry(false, realCmb, mockCmb, genericMock);
        assertEquals("CMB_MOCK", closedGate.resolveCode(null, "CMB"));

        BankDataAdapterRegistry noRealAdapter = registry(true, mockCmb, genericMock);
        assertEquals("CMB_MOCK", noRealAdapter.resolveCode(null, "CMB"));
    }

    @Test
    void providerWithoutBrandMockFallsBackToProviderCodeThenGenericMock() {
        BankDataAdapterRegistry closedGate = registry(false, realCmb, genericMock);
        assertEquals("CMB", closedGate.resolveCode(null, "CMB"));

        BankDataAdapterRegistry onlyGeneric = registry(false, genericMock);
        assertEquals("MOCK", onlyGeneric.resolveCode(null, "CITIC"));
        assertEquals("MOCK", onlyGeneric.resolveCode(null, null));
    }

    @Test
    void explicitUnknownCodeIsRejectedInsteadOfSilentlyBecomingMock() {
        BankDataAdapterRegistry registry = registry(true, realCmb, mockCmb, genericMock);

        assertThrows(BusinessException.class, () -> registry.resolveCode("REAL_NOT_REGISTERED", "CMB"));
        assertThrows(BusinessException.class, () -> registry.require("REAL_NOT_REGISTERED"));
        assertEquals("MOCK", registry.resolveCode("MOCK", null));
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
