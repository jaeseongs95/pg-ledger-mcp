package org.pgledger.admin.security;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The wait that slows the check of a token down when the global budget of checks is used up (see {@link LoginAttempts}).
 * It is a component so that the provider takes it as a dependency, and a test that needs the delay to be a fact of the
 * check, not of the clock, gives the provider its own.
 *
 * <p>{@link Thread#sleep(java.time.Duration)}: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html
 */
@Component
public class LoginDelay {

    /**
     * Waits for {@code delay} (no wait for a zero delay).
     *
     * @throws InterruptedException when the waiting thread is interrupted (the server is stopping); the caller decides
     *         what the unfinished wait means
     */
    public void await(Duration delay) throws InterruptedException {
        if (!delay.isZero()) {
            Thread.sleep(delay);
        }
    }
}
