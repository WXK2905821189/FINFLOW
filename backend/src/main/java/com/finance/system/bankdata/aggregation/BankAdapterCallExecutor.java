package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Future real-bank call boundary. It defaults closed and supplies bounded rate,
 * timeout and retry behavior without adding any SDK or network dependency.
 */
@Component
public class BankAdapterCallExecutor {

    private final BankAdapterCallProperties properties;
    private final ExecutorService executor;
    private final Clock clock;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    @Autowired
    public BankAdapterCallExecutor(BankAdapterCallProperties properties) {
        this(properties, Executors.newFixedThreadPool(4), Clock.systemUTC());
    }

    BankAdapterCallExecutor(BankAdapterCallProperties properties, ExecutorService executor, Clock clock) {
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    BankAdapterCallOutcome invoke(BankDataAdapter adapter, BankDataSyncContext context) {
        if (adapter.executionMode() == BankAdapterExecutionMode.SIMULATED) {
            return BankAdapterCallOutcome.response(adapter.collect(context));
        }
        if (!properties.isRealAdaptersEnabled()) {
            return BankAdapterCallOutcome.terminal(BankDataStatus.UNKNOWN,
                    "Real bank adapter is disabled until sandbox approval");
        }
        String rateKey = adapter.adapterCode() + ":" + (context.connectionId() == null ? "none" : context.connectionId());
        if (!tryAcquire(rateKey)) {
            return BankAdapterCallOutcome.terminal(BankDataStatus.PENDING,
                    "Adapter call deferred by the configured request limit");
        }
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                return BankAdapterCallOutcome.response(callWithTimeout(adapter, context));
            } catch (TimeoutException exception) {
                lastFailure = exception;
                if (attempt == properties.getMaxAttempts()) {
                    return BankAdapterCallOutcome.terminal(BankDataStatus.TIMEOUT,
                            "Adapter call timed out after bounded retries");
                }
            } catch (Exception exception) {
                lastFailure = exception;
                if (!isTransient(exception) || attempt == properties.getMaxAttempts()) {
                    return BankAdapterCallOutcome.terminal(BankDataStatus.FAILED,
                            "Adapter call failed before a bank response was available");
                }
            }
            if (!backoff(attempt)) {
                return BankAdapterCallOutcome.terminal(BankDataStatus.UNKNOWN,
                        "Adapter retry was interrupted and requires manual handling");
            }
        }
        return BankAdapterCallOutcome.terminal(BankDataStatus.UNKNOWN,
                lastFailure == null ? "Adapter call requires manual handling" : "Adapter call requires manual handling after retry");
    }

    private BankDataCollection callWithTimeout(BankDataAdapter adapter, BankDataSyncContext context)
            throws TimeoutException, ExecutionException, InterruptedException {
        Callable<BankDataCollection> action = () -> adapter.collect(context);
        Future<BankDataCollection> future = executor.submit(action);
        try {
            return future.get(properties.getTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        }
    }

    private synchronized boolean tryAcquire(String key) {
        long minute = clock.millis() / 60_000;
        RateWindow existing = rateWindows.get(key);
        RateWindow active = existing == null || existing.minute != minute ? new RateWindow(minute, 0) : existing;
        if (active.count >= properties.getMaxRequestsPerMinute()) return false;
        rateWindows.put(key, new RateWindow(minute, active.count + 1));
        return true;
    }

    private boolean backoff(int completedAttempt) {
        long delay = Math.min(properties.getBaseBackoffMillis() * (1L << Math.min(completedAttempt - 1, 8)), 10_000);
        if (delay == 0) return true;
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isTransient(Throwable exception) {
        Throwable current = exception instanceof ExecutionException ? exception.getCause() : exception;
        while (current != null) {
            if (current instanceof BankAdapterTransientException || current instanceof SocketTimeoutException
                    || current instanceof ConnectException || current instanceof IOException) return true;
            current = current.getCause();
        }
        return false;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record RateWindow(long minute, int count) { }
}
