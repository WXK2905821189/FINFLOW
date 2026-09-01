package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankAdapterCallExecutorTest {

    private final BankDataSyncContext context = new BankDataSyncContext(1L, 2L, 3L, "TASK", "REQ");

    @Test
    void realAdapterIsClosedByDefaultWithoutInvokingIt() {
        BankAdapterCallProperties properties = new BankAdapterCallProperties();
        AtomicInteger calls = new AtomicInteger();
        BankAdapterCallExecutor executor = executor(properties);
        try {
            BankAdapterCallOutcome result = executor.invoke(realAdapter(calls, 0), context);
            assertEquals(BankDataStatus.UNKNOWN, result.terminalStatus());
            assertEquals(0, calls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void retriesOnlyTransientFailuresWithinAttemptLimit() {
        BankAdapterCallProperties properties = new BankAdapterCallProperties();
        properties.setRealAdaptersEnabled(true);
        properties.setMaxAttempts(3);
        properties.setBaseBackoffMillis(0);
        AtomicInteger calls = new AtomicInteger();
        BankAdapterCallExecutor executor = executor(properties);
        try {
            BankAdapterCallOutcome result = executor.invoke(realAdapter(calls, 2), context);
            assertEquals(null, result.terminalStatus());
            assertEquals(3, calls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void returnsTimeoutAndCancelsSlowCall() {
        BankAdapterCallProperties properties = new BankAdapterCallProperties();
        properties.setRealAdaptersEnabled(true);
        properties.setMaxAttempts(1);
        properties.setTimeoutMillis(20);
        AtomicInteger calls = new AtomicInteger();
        BankDataAdapter slow = new BankDataAdapter() {
            @Override public String adapterCode() { return "SLOW_REAL"; }
            @Override public BankAdapterExecutionMode executionMode() { return BankAdapterExecutionMode.REAL; }
            @Override public BankDataCollection collect(BankDataSyncContext ignored) {
                calls.incrementAndGet();
                try { Thread.sleep(200); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return new BankDataCollection("REQ", List.of(), List.of());
            }
        };
        BankAdapterCallExecutor executor = executor(properties);
        try {
            BankAdapterCallOutcome result = executor.invoke(slow, context);
            assertEquals(BankDataStatus.TIMEOUT, result.terminalStatus());
            assertEquals(1, calls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rateLimitIsPerAdapterAndConnectionWindow() {
        BankAdapterCallProperties properties = new BankAdapterCallProperties();
        properties.setRealAdaptersEnabled(true);
        properties.setMaxRequestsPerMinute(1);
        AtomicInteger calls = new AtomicInteger();
        BankDataAdapter adapter = realAdapter(calls, 0);
        BankAdapterCallExecutor executor = executor(properties);
        try {
            assertEquals(null, executor.invoke(adapter, context).terminalStatus());
            assertEquals(BankDataStatus.PENDING, executor.invoke(adapter, context).terminalStatus());
            assertEquals(1, calls.get());
        } finally {
            executor.shutdown();
        }
    }

    private BankAdapterCallExecutor executor(BankAdapterCallProperties properties) {
        return new BankAdapterCallExecutor(properties, Executors.newFixedThreadPool(2),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private BankDataAdapter realAdapter(AtomicInteger calls, int transientFailures) {
        return new BankDataAdapter() {
            @Override public String adapterCode() { return "REAL_TEST"; }
            @Override public BankAdapterExecutionMode executionMode() { return BankAdapterExecutionMode.REAL; }
            @Override public BankDataCollection collect(BankDataSyncContext ignored) {
                if (calls.getAndIncrement() < transientFailures) throw new BankAdapterTransientException("temporary");
                return new BankDataCollection("REQ", List.of(), List.of());
            }
        };
    }
}
