package org.pgledger.admin.security;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * The lock after failed token attempts, per client address: {@code maxFailures} failures within {@code window} lock the
 * address for {@code lockPeriod}; a success clears the count. The counters and the locks are entries of two Spring
 * Cache (Caffeine) caches that expire by themselves on the clock of the application, so the memory is bounded
 * (a fixed number of addresses) and a test can move time.
 *
 * <p>Why this is ours (principle 2.1): Spring Security publishes {@code AuthenticationFailureBadCredentialsEvent} and
 * knows a locked account ({@code LockedException}, {@code UserDetails#isAccountNonLocked}) but ships no counter that
 * locks by client address. This statement is from the reference documentation as remembered and was not re-read for
 * this change (reported as unverified). The check runs in {@link AdminTokenProvider}, the one provider of the chain,
 * so every way of presenting the token (login form, bearer) is covered.
 */
@Component
public class LoginAttempts {

    private static final int MAX_CLIENTS = 10_000;

    private final Cache failures;
    private final Cache locks;
    private final int maxFailures;
    private final Clock clock;

    public LoginAttempts(LedgerAdminProperties properties, Ticker ticker, Clock clock) {
        LedgerAdminProperties.Lockout lockout = properties.lockout();
        this.maxFailures = lockout.maxFailures();
        this.clock = clock;
        this.failures = new CaffeineCache("login-failures", Caffeine.newBuilder().expireAfterWrite(lockout.window())
                .maximumSize(MAX_CLIENTS).ticker(ticker).executor(Runnable::run).build());
        this.locks = new CaffeineCache("login-locks", Caffeine.newBuilder().expireAfterWrite(lockout.lockPeriod())
                .maximumSize(MAX_CLIENTS).ticker(ticker).executor(Runnable::run).build());
    }

    public boolean isLocked(String client) {
        return locks.get(client) != null;
    }

    /** Counts a failure; the failure that reaches the limit starts the lock. */
    public void failed(String client) {
        AtomicInteger count = failures.get(client, AtomicInteger::new);
        if (count.incrementAndGet() >= maxFailures) {
            locks.put(client, Instant.now(clock));
            failures.evict(client);
        }
    }

    public void succeeded(String client) {
        failures.evict(client);
    }
}
