package org.pgledger.application.gate;

import java.util.List;
import java.util.function.Supplier;
import org.pgledger.domain.error.LedgerException;

/**
 * What a write touches, as far as it could be found out before the caller is trusted: the targets, or the failure
 * that looking them up produced. The failure is not raised until the gate has checked the actor, so an unknown id
 * never answers before a missing attestation does.
 */
public sealed interface Resolution {

    record Resolved(List<Target> targets) implements Resolution {

        public Resolved {
            targets = List.copyOf(targets);
        }
    }

    record Unresolved(LedgerException failure) implements Resolution {
    }

    /** Runs the lookup; a business failure of the lookup is kept as data. */
    static Resolution of(Supplier<List<Target>> lookup) {
        try {
            return new Resolved(lookup.get());
        } catch (LedgerException failure) {
            return new Unresolved(failure);
        }
    }
}
