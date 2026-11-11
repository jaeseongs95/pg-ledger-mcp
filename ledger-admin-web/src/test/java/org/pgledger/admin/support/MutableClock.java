package org.pgledger.admin.support;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A UTC clock that a test moves (the grant expiry and the stamps follow it), and the monotonic ticker next to it (the lock
 * after failed attempts counts on the ticker). {@link #advance} moves both, as time does; {@link #jumpWallClock} moves only
 * the wall clock, as somebody setting the system time would. Neither moves by itself.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T10:00:00Z"));
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    /** Time passes: the clock and the ticker move on by the same amount. */
    public void advance(Duration by) {
        now.updateAndGet(current -> current.plus(by));
        nanos.addAndGet(by.toNanos());
    }

    /** The system time is set: only the wall clock moves. */
    public void jumpWallClock(Duration by) {
        now.updateAndGet(current -> current.plus(by));
    }

    /** The monotonic time source of the application under test. */
    public Ticker ticker() {
        return nanos::get;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
