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
 * The lock after failed token attempts. Two limits, both counted on the monotonic {@link Ticker} (production:
 * {@code System.nanoTime}), never on the wall clock, so a change of the system time cannot end or extend a lock:
 *
 * <ul>
 * <li>per client: {@code maxFailures} attempts within {@code window} lock that client for {@code lockPeriod}. The
 *     client is its IPv4 address or the /64 prefix of its IPv6 address (the owner of an IPv6 network has 2^64 addresses
 *     of its own, so one address per attempt would be no limit at all). An attempt is counted when it is admitted and
 *     before the token is compared ({@link #admit}), in one atomic step with the check of the lock, so concurrent
 *     attempts cannot exceed the limit; a success clears the count.</li>
 * <li>global: {@code globalMaxFailures} failures of all clients together within {@code window} lock every client for
 *     {@code lockPeriod}. It does not depend on the client address, so rotating addresses, or filling the bounded table
 *     of known clients so that entries are evicted, does not give an attacker more attempts than this cap.</li>
 * </ul>
 *
 * <p>Why this is ours (principle 2.1): Spring Security publishes authentication events
 * ({@code AuthenticationFailureBadCredentialsEvent} and the others) but has no lockout feature, see
 * https://docs.spring.io/spring-security/reference/servlet/authentication/events.html . The table of clients is a
 * Caffeine {@link Cache} (bounded, entries expire by themselves on the ticker). Spring's cache abstraction
 * ({@code org.springframework.cache.Cache}, https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/Cache.html)
 * offers get, put and evict but no atomic update of an entry, which the count-and-check needs; Caffeine's
 * {@code Cache.asMap().compute} is atomic per key
 * (https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/Cache.html).
 * The check runs in {@link AdminTokenProvider}, the one provider of the chain, so every way of presenting the token
 * (login form, bearer) is covered.
 */
@Component
public class LoginAttempts {

    private static final int MAX_CLIENTS = 10_000;
    private static final int IPV6_PREFIX_BYTES = 8;

    /** The state of one counter: attempts counted in the current window, when the window ends and until when it is locked (nanoseconds of the ticker, 0 = not locked). */
    private record Counter(int used, long windowEnds, long lockedUntil) {

        static final Counter NONE = new Counter(0, 0, 0);

        boolean lockedAt(long now) {
            return lockedUntil > now;
        }

        /** The counter after one more count at {@code now}: a finished window or a finished lock starts afresh. */
        Counter counted(long now, int limit, long window, long lock) {
            boolean fresh = windowEnds <= now || lockedUntil != 0;
            int count = (fresh ? 0 : used) + 1;
            long ends = fresh ? now + window : windowEnds;
            return new Counter(count, ends, count >= limit ? now + lock : 0);
        }
    }

    private final Cache<String, Counter> clients;
    private final AtomicReference<Counter> everyone = new AtomicReference<>(Counter.NONE);
    private final Ticker ticker;
    private final int maxFailures;
    private final int globalMaxFailures;
    private final long window;
    private final long lock;

    public LoginAttempts(LedgerAdminProperties properties, Ticker ticker) {
        LedgerAdminProperties.Lockout lockout = properties.lockout();
        this.ticker = ticker;
        this.maxFailures = lockout.maxFailures();
        this.globalMaxFailures = lockout.globalMaxFailures();
        this.window = lockout.window().toNanos();
        this.lock = lockout.lockPeriod().toNanos();
        Duration keep = lockout.window().compareTo(lockout.lockPeriod()) > 0 ? lockout.window() : lockout.lockPeriod();
        this.clients = Caffeine.newBuilder().expireAfterWrite(keep).maximumSize(MAX_CLIENTS).ticker(ticker)
                .executor(Runnable::run).build();
    }

    /**
     * Counts an attempt of the client and tells whether it may go on to the comparison of the token: false while the
     * client or everybody is locked (an attempt of a locked client is not counted again). The count and the check of the
     * lock are one atomic step per client. The attempt that reaches the limit is admitted and starts the lock for the
     * next ones.
     */
    public boolean admit(String client) {
        long now = ticker.read();
        if (everyone.get().lockedAt(now)) {
            return false;
        }
        AtomicBoolean refused = new AtomicBoolean();
        clients.asMap().compute(keyOf(client), (key, current) -> {
            if (current != null && current.lockedAt(now)) {
                refused.set(true);
                return current;
            }
            return (current == null ? Counter.NONE : current).counted(now, maxFailures, window, lock);
        });
        return !refused.get();
    }

    /** Counts a failed attempt of any client towards the cap that does not depend on the client address. */
    public void failed() {
        long now = ticker.read();
        everyone.updateAndGet(current -> current.lockedAt(now) ? current
                : current.counted(now, globalMaxFailures, window, lock));
    }

    /** A right token: the count of the client starts again. */
    public void succeeded(String client) {
        clients.invalidate(keyOf(client));
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
