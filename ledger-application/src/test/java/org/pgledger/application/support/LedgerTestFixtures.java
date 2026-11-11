package org.pgledger.application.support;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * What the tests of one Spring context share: the numbers of their projects and the fixtures that a whole test
 * class builds once. It is a bean of the test context, so every context (and every restart) has its own.
 */
public final class LedgerTestFixtures {

    private final AtomicInteger projects = new AtomicInteger();
    private final ConcurrentHashMap<String, Object> built = new ConcurrentHashMap<>();

    /** A number no other scenario of this context has used. */
    int nextProjectNumber() {
        return projects.incrementAndGet();
    }

    /** Builds a fixture the first time it is asked for and returns the same one afterwards. */
    <T> T once(String key, Supplier<T> build) {
        @SuppressWarnings("unchecked")
        T value = (T) built.computeIfAbsent(key, k -> build.get());
        return value;
    }
}
