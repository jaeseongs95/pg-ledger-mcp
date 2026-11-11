package org.pgledger.admin.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.stereotype.Component;

/**
 * The limits on token attempts. Both are counted on the monotonic {@link Ticker} (production: {@code System.nanoTime}),
 * never on the wall clock, so a change of the system time cannot end or extend a lock. Times of the ticker are compared
 * by their difference ({@code end - now}), as the documentation of {@link System#nanoTime} asks, because a value of the
 * ticker may be negative and may wrap around:
 *
 * <ul>
 * <li>per client: {@code maxFailures} attempts within {@code window} lock that client for {@code lockPeriod}. The
 *     client is its IPv4 address or the /64 prefix of its IPv6 address (the owner of an IPv6 network has 2^64 addresses
 *     of its own, so one address per attempt would be no limit at all). An attempt is counted when it is admitted and
 *     before the token is compared ({@link #admit}), in one atomic step with the check of the lock, so concurrent
 *     attempts cannot exceed the limit; a success clears the count. A locked client is refused, whatever its token is.</li>
 * <li>global: {@code globalMaxFailures} token checks of all clients together within {@code window} are the budget; every
 *     check beyond it is <em>delayed</em> by {@code globalDelay} (it is still made, and the right token still logs in).
 *     The budget does not depend on the client address, so rotating addresses, or filling the bounded table of known
 *     clients so that entries are evicted, does not let an attacker check faster than the budget allows for long. It is a
 *     throttle and not a lock on purpose: a lock that anybody can trigger with wrong tokens from many addresses locks
 *     the owner out as well (a denial of service of the only login). The check is counted when it is admitted, in one
 *     atomic step with the decision about the delay, so concurrent checks cannot overshoot the budget; a success gives
 *     its count back, so the owner's own successful logins do not use the budget up.</li>
 * </ul>
 *
 * <p>Why this is ours (principle 2.1): Spring Security publishes authentication events
 * ({@code AuthenticationFailureBadCredentialsEvent} and the others) but has no lockout or throttle feature, see
 * https://docs.spring.io/spring-security/reference/servlet/authentication/events.html . The table of clients is a
 * Caffeine {@link Cache} (bounded, entries expire by themselves on the ticker). Spring's cache abstraction
 * ({@code org.springframework.cache.Cache}, https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/Cache.html)
 * offers get, put and evict but no atomic update of an entry, which the count-and-check needs; Caffeine's
 * {@code Cache.asMap().compute} runs its function as one atomic operation on the entry
 * (https://github.com/ben-manes/caffeine/wiki/Compute: "Map compute methods provides the ability to perform logic
 * within an atomic operation that may create, update, or remove an entry"). The global budget is one
 * {@link AtomicReference#updateAndGet} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html).
 * The check runs in {@link AdminTokenProvider}, the one provider of the chain, so every way of presenting the token
 * (login form, bearer) is covered.
 */
@Component
public class LoginAttempts {

    private static final int MAX_CLIENTS = 10_000;
    private static final int IPV6_PREFIX_BYTES = 8;

    /**
     * What an attempt may do: {@code allowed} is false while its client is locked (the token is not compared at all);
     * otherwise the token is compared after {@code delay} (zero while the global budget lasts).
     */
    public record Admission(boolean allowed, Duration delay) {

        static final Admission REFUSED = new Admission(false, Duration.ZERO);
        static final Admission IMMEDIATE = new Admission(true, Duration.ZERO);
    }

    /**
     * The state of the lock of one client: attempts counted in the current window ({@code used} is 0 before the first
     * one), when the window ends, and whether the client is locked and until when (nanoseconds of the ticker;
     * {@code lockedUntil} means something only while {@code locked} is true, so no ticker value stands for "no lock").
     */
    private record Counter(int used, long windowEnds, boolean locked, long lockedUntil) {

        static final Counter NONE = new Counter(0, 0, false, 0);

        boolean lockedAt(long now) {
            return locked && lockedUntil - now > 0;
        }

        /** The counter after one more count at {@code now}: no window yet, a finished window or a finished lock starts afresh. */
        Counter counted(long now, int limit, long window, long lock) {
            boolean fresh = used == 0 || locked || windowEnds - now <= 0;
            int count = (fresh ? 0 : used) + 1;
            long ends = fresh ? now + window : windowEnds;
            boolean lockNow = count >= limit;
            return new Counter(count, ends, lockNow, lockNow ? now + lock : 0);
        }
    }

    /** The global budget: checks counted in the current window ({@code used} is 0 before the first one) and when it ends. */
    private record Budget(int used, long windowEnds) {

        static final Budget NONE = new Budget(0, 0);

        Budget counted(long now, long window) {
            boolean fresh = used == 0 || windowEnds - now <= 0;
            return new Budget((fresh ? 0 : used) + 1, fresh ? now + window : windowEnds);
        }

        /** One count given back (the check succeeded); never below none. */
        Budget refunded() {
            return used > 0 ? new Budget(used - 1, windowEnds) : this;
        }
    }

    private final Cache<String, Counter> clients;
    private final AtomicReference<Budget> everyone = new AtomicReference<>(Budget.NONE);
    private final Ticker ticker;
    private final int maxFailures;
    private final int globalBudget;
    private final Duration globalDelay;
    private final long window;
    private final long lock;

    public LoginAttempts(LedgerAdminProperties properties, Ticker ticker) {
        LedgerAdminProperties.Lockout lockout = properties.lockout();
        this.ticker = ticker;
        this.maxFailures = lockout.maxFailures();
        this.globalBudget = lockout.globalMaxFailures();
        this.globalDelay = lockout.globalDelay();
        this.window = lockout.window().toNanos();
        this.lock = lockout.lockPeriod().toNanos();
        Duration keep = lockout.window().compareTo(lockout.lockPeriod()) > 0 ? lockout.window() : lockout.lockPeriod();
        this.clients = Caffeine.newBuilder().expireAfterWrite(keep).maximumSize(MAX_CLIENTS).ticker(ticker)
                .executor(Runnable::run).build();
    }

    /**
     * Counts an attempt of the client and tells whether, and after how long, it may go on to the comparison of the
     * token: not at all while the client is locked (an attempt of a locked client is not counted again, neither for the
     * client nor for the budget); otherwise after the global delay when the attempt is beyond the global budget. The
     * count and the check of the lock are one atomic step per client, and the count of the budget and the decision about
     * the delay are one atomic step for everybody. The attempt that reaches the limit of the client is admitted and
     * starts the lock for the next ones.
     */
    public Admission admit(String client) {
        long now = ticker.read();
        AtomicBoolean refused = new AtomicBoolean();
        clients.asMap().compute(keyOf(client), (key, current) -> {
            if (current != null && current.lockedAt(now)) {
                refused.set(true);
                return current;
            }
            return (current == null ? Counter.NONE : current).counted(now, maxFailures, window, lock);
        });
        if (refused.get()) {
            return Admission.REFUSED;
        }
        Budget counted = everyone.updateAndGet(current -> current.counted(now, window));
        return counted.used() > globalBudget ? new Admission(true, globalDelay) : Admission.IMMEDIATE;
    }

    /** A right token: the count of the client starts again, and the check gives its count back to the global budget. */
    public void succeeded(String client) {
        clients.invalidate(keyOf(client));
        everyone.updateAndGet(Budget::refunded);
    }

    /**
     * The key of a client address: an IPv4 address as it is (an IPv4-mapped IPv6 address is the IPv4 address), an IPv6
     * address as its /64 prefix, no address as one shared key.
     *
     * @throws IllegalArgumentException when the text is not an IP address literal (the servlet container gives
     *         literals only)
     */
    static String keyOf(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        InetAddress parsed = InetAddress.ofLiteral(address.strip());
        if (parsed instanceof Inet6Address v6) {
            return "v6:" + HexFormat.of().formatHex(v6.getAddress(), 0, IPV6_PREFIX_BYTES);
        }
        return parsed.getHostAddress();
    }
}
