package com.finance.system.security;

import com.finance.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleServiceTest {

    private static final Instant START = Instant.parse("2026-09-07T02:00:00Z");

    /** Mutable clock so a single service instance can be moved through the 15-minute window. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        void advance(Duration delta) { now.updateAndGet(current -> current.plus(delta)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void locksUsernameAfterFiveConsecutiveFailures() {
        LoginThrottleService service = new LoginThrottleService(new MutableClock());
        for (int i = 0; i < 5; i++) {
            service.recordFailure("LockedUser", "10.0.0.1");
        }
        assertThatThrownBy(() -> service.ensureUsernameNotLocked("lockeduser"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Account or password is invalid");
    }

    @Test
    void lockWindowSlidesFromLastFailureAndExpires() {
        MutableClock clock = new MutableClock();
        LoginThrottleService service = new LoginThrottleService(clock);
        service.recordFailure("u1", null);
        service.recordFailure("u1", null);
        clock.advance(Duration.ofMinutes(10));
        service.recordFailure("u1", null);
        service.recordFailure("u1", null);
        // 4 consecutive failures within the window: still allowed
        assertThatCode(() -> service.ensureUsernameNotLocked("u1")).doesNotThrowAnyException();
        // 5th failure locks from NOW (sliding window anchored at the last failure)
        service.recordFailure("u1", null);
        assertThatThrownBy(() -> service.ensureUsernameNotLocked("u1"))
                .isInstanceOf(BusinessException.class);
        // 14 minutes after the last failure: still locked
        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> service.ensureUsernameNotLocked("u1"))
                .isInstanceOf(BusinessException.class);
        // 16 minutes after the last failure: lock expired
        clock.advance(Duration.ofMinutes(2));
        assertThatCode(() -> service.ensureUsernameNotLocked("u1")).doesNotThrowAnyException();
    }

    @Test
    void successResetsFailureCounter() {
        LoginThrottleService service = new LoginThrottleService(new MutableClock());
        for (int i = 0; i < 4; i++) {
            service.recordFailure("u2", null);
        }
        service.recordSuccess("u2");
        for (int i = 0; i < 4; i++) {
            service.recordFailure("u2", null);
        }
        assertThatCode(() -> service.ensureUsernameNotLocked("u2"))
                .doesNotThrowAnyException();
        service.recordFailure("u2", null); // 5th consecutive failure after reset
        assertThatThrownBy(() -> service.ensureUsernameNotLocked("u2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ipThrottleCountsOnlyFailuresAndYields429() {
        MutableClock clock = new MutableClock();
        LoginThrottleService service = new LoginThrottleService(clock);
        String ip = "203.0.113.9";
        for (int i = 0; i < LoginThrottleService.MAX_IP_FAILURES_PER_MINUTE; i++) {
            service.recordFailure("victim-" + i, ip);
            service.recordSuccess("victim-" + i); // successes must not count toward the IP window
        }
        assertThatThrownBy(() -> service.ensureIpAllowed(ip))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many requests");
        // Different IP unaffected; same IP after the one-minute window passes is fine again
        assertThatCode(() -> service.ensureIpAllowed("203.0.113.10"))
                .doesNotThrowAnyException();
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThatCode(() -> service.ensureIpAllowed(ip)).doesNotThrowAnyException();
    }

    @Test
    void concurrentFailuresDoNotExceedThresholdSemantics() throws Exception {
        LoginThrottleService service = new LoginThrottleService(new MutableClock());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            pool.submit(() -> {
                service.recordFailure("race", "198.51.100.1");
                latch.countDown();
            });
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThatThrownBy(() -> service.ensureUsernameNotLocked("race"))
                .isInstanceOf(BusinessException.class);
    }
}
