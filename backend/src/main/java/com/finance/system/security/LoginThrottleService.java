package com.finance.system.security;

import com.finance.system.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for the login endpoint (GAP-4, module doc 2026-09-07).
 *
 * <ul>
 *   <li><b>Per-username lockout</b>: 5 consecutive failures lock the identity for 15 minutes
 *       (window slides from the LAST failure). While locked, the endpoint answers with the same
 *       generic 401 as a wrong password, so the lockout cannot be used to enumerate accounts.</li>
 *   <li><b>Per-IP failure throttle</b>: only FAILED logins count toward the per-IP window
 *       ({@value #MAX_IP_FAILURES_PER_MINUTE}/minute → 429). Successful logins never count, so
 *       shared egress IPs (office NAT, reverse proxy) cannot lock out legitimate users.</li>
 * </ul>
 *
 * <p>State is intentionally JVM-local: the production deployment is a single app container
 * (ECS docker compose), and clearing throttle state on restart is an accepted trade-off
 * against adding a shared store dependency for this concern.
 */
@Service
public class LoginThrottleService {

    static final int MAX_FAILURES_PER_USERNAME = 5;
    static final Duration USERNAME_LOCK_WINDOW = Duration.ofMinutes(15);
    static final int MAX_IP_FAILURES_PER_MINUTE = 50;
    static final Duration IP_WINDOW = Duration.ofMinutes(1);

    private record FailureState(int count, Instant lastFailureAt) {}

    private final ConcurrentHashMap<String, FailureState> failuresByUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Instant>> failuresByIp = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottleService() {
        this(Clock.systemUTC());
    }

    /** Package-visible for tests to advance time deterministically. */
    LoginThrottleService(Clock clock) {
        this.clock = clock;
    }

    /** Throws the same generic 401 as a wrong password while the identity is locked. */
    public void ensureUsernameNotLocked(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        FailureState state = failuresByUsername.get(key(username));
        if (state != null && state.count() >= MAX_FAILURES_PER_USERNAME
                && now().isBefore(state.lastFailureAt().plus(USERNAME_LOCK_WINDOW))) {
            throw new BusinessException(401, "Account or password is invalid");
        }
    }

    /** Throws 429 when this source IP accumulated too many failed logins inside the window. */
    public void ensureIpAllowed(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        Deque<Instant> window = failuresByIp.get(ip);
        if (window == null) {
            return;
        }
        synchronized (window) {
            Instant cutoff = now().minus(IP_WINDOW);
            while (!window.isEmpty() && window.peek().isBefore(cutoff)) {
                window.poll();
            }
            if (window.size() >= MAX_IP_FAILURES_PER_MINUTE) {
                throw new BusinessException(429, "Too many requests, try again later");
            }
        }
    }

    public void recordFailure(String username, String ip) {
        if (username != null && !username.isBlank()) {
            failuresByUsername.compute(key(username), (k, state) -> {
                Instant now = now();
                if (state == null || !now.isBefore(state.lastFailureAt().plus(USERNAME_LOCK_WINDOW))) {
                    return new FailureState(1, now);
                }
                return new FailureState(state.count() + 1, now);
            });
        }
        if (ip != null && !ip.isBlank()) {
            Deque<Instant> window = failuresByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
            synchronized (window) {
                window.addLast(now());
            }
        }
    }

    public void recordSuccess(String username) {
        if (username != null && !username.isBlank()) {
            failuresByUsername.remove(key(username));
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private String key(String username) {
        return username.trim().toLowerCase();
    }
}
