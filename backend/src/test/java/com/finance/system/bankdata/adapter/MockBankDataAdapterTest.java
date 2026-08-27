package com.finance.system.bankdata.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MockBankDataAdapterTest {

    private final MockBankDataAdapter adapter = new MockBankDataAdapter();

    @Test
    void returnsStableScopedDataWithoutExternalCalls() {
        BankDataSyncContext context = new BankDataSyncContext(1L, null, 2L, "TASK-1", "REQ-1");

        BankDataCollection first = adapter.collect(context);
        BankDataCollection second = adapter.collect(context);

        assertEquals("MOCK", adapter.adapterCode());
        assertEquals(first, second);
        assertEquals(1, first.entries().size());
        assertEquals(2L, first.entries().get(0).bankAccountId());
        assertNotNull(first.entries().get(0).amount());
    }
}
