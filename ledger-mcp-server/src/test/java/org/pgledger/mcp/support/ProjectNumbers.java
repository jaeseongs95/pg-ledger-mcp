package org.pgledger.mcp.support;

import java.util.concurrent.atomic.AtomicInteger;

/** Hands out the numbers that make the project of each scenario unique within one test context. */
public final class ProjectNumbers {

    private final AtomicInteger last = new AtomicInteger();

    public int next() {
        return last.incrementAndGet();
    }
}
