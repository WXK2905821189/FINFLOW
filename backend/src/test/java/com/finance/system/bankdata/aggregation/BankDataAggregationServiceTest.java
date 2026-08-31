package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankDataAggregationServiceTest {

    private final BankDataAggregationService service = new BankDataAggregationService(
            new BankDataAdapterRegistry(List.of(new StubAdapter("CITIC_MOCK", "C", "AAAAAAA"),
                    new StubAdapter("CMB_MOCK", "IN", "AAAAAAE"))));

    @Test
    void routesByAdapterAndConvertsVendorFieldsToUnifiedModel() {
        BankDataAggregationResult result = service.collect(context(), "citic_mock");

        assertEquals("CITIC_MOCK", result.adapterCode());
        assertEquals("FINFLOW-BANKDATA-V1", result.mappingVersion());
        assertEquals(BankDataStatus.SUCCESS, result.status());
        assertEquals("INCOME", result.collection().entries().get(0).direction());
        assertEquals("CNY", result.collection().entries().get(0).currency());
    }

    @Test
    void mapsPendingAndEmptyWithoutTurningEitherIntoSuccess() {
        BankDataAggregationResult pending = service.collect(context(), "CMB_MOCK");
        assertEquals(BankDataStatus.PENDING, pending.status());
        assertEquals("Bank result is pending reconciliation", pending.safeErrorSummary());

        BankDataAggregationService emptyService = new BankDataAggregationService(new BankDataAdapterRegistry(
                List.of(new StubAdapter("EMPTY_MOCK", "IN", "SUCCESS", true))));
        assertEquals(BankDataStatus.EMPTY, emptyService.collect(context(), "EMPTY_MOCK").status());
    }

    @Test
    void rejectsUnknownAdapterAndResolvesProviderBrandMock() {
        assertEquals("CITIC_MOCK", service.resolveAdapterCode(null, "citic"));
        assertThrows(BusinessException.class, () -> service.collect(context(), "BANK_PROD"));
    }

    private BankDataSyncContext context() {
        return new BankDataSyncContext(1L, 2L, 3L, "TASK-1", "REQ-1",
                LocalDateTime.of(2026, 8, 27, 0, 0), LocalDateTime.of(2026, 8, 28, 0, 0),
                1, null, 100, "STATEMENT");
    }

    private static final class StubAdapter implements BankDataAdapter {
        private final String code;
        private final String direction;
        private final String status;
        private final boolean empty;

        private StubAdapter(String code, String direction, String status) {
            this(code, direction, status, false);
        }

        private StubAdapter(String code, String direction, String status, boolean empty) {
            this.code = code;
            this.direction = direction;
            this.status = status;
            this.empty = empty;
        }

        @Override
        public String adapterCode() { return code; }

        @Override
        public BankDataCollection collect(BankDataSyncContext context) {
            if (empty) return new BankDataCollection("REQ-EMPTY", List.of(), List.of(), true, "ignored", status, status);
            return new BankDataCollection("REQ-1", List.of(new BankDataEntry("REQ-1", "S-1",
                    context.bankAccountId(), context.windowStart(), direction, new BigDecimal("1.00"), "cny",
                    "p", "acct", "summary")), List.of(), false, null, status, status);
        }
    }
}
