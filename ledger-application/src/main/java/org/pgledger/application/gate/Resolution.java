package org.pgledger.application.gate;

import java.util.List;
import org.pgledger.domain.error.LedgerException;

/**
 * What a write touches, as far as it could be found out before the caller is trusted: the targets, or the failure
 * that their absence is. The failure is a value here; it is not raised until the gate has checked the actor, so an
 * unknown id never answers before a missing attestation does. The lookups that produce it return {@code Optional}
 * rows, so no exception is thrown and caught to get to it.
 */
public sealed interface Resolution {

    record Resolved(List<Target> targets) implements Resolution {

        public Resolved {
            targets = List.copyOf(targets);
        }
    }

    record Unresolved(LedgerException failure) implements Resolution {
    }

    static Resolution of(Target... targets) {
        return new Resolved(List.of(targets));
    }

    static Resolution unresolved(LedgerException failure) {
        return new Unresolved(failure);
    }
}
