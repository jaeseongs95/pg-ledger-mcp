package org.pgledger.domain.context;

import java.util.concurrent.Callable;
import org.pgledger.domain.value.ActorId;

/**
 * The actor of the current request, carried by a {@link ScopedValue} so it cannot leak between calls and is
 * unbound after the call. Auditing ({@code created_by}) reads it.
 */
public final class ActorScope {

    public static final ScopedValue<ActorId> CURRENT = ScopedValue.newInstance();

    private ActorScope() {
    }

    /** Runs {@code action} with {@code actor} bound. */
    public static <T> T callAs(ActorId actor, Callable<? extends T> action) throws Exception {
        return ScopedValue.where(CURRENT, actor).call(action::call);
    }

    /** Runs {@code action} with {@code actor} bound. */
    public static void runAs(ActorId actor, Runnable action) {
        ScopedValue.where(CURRENT, actor).run(action);
    }
}
