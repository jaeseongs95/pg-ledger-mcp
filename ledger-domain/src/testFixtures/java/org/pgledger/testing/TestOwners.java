package org.pgledger.testing;

import org.pgledger.domain.value.OwnerIdentity;

/** The owner identity that the tests of the services use as the signed-in owner. */
public final class TestOwners {

    /** The actor of an owner operation in a test; the audit trail shows it as {@code test:owner}. */
    public static final OwnerIdentity OWNER = OwnerIdentity.of("test:owner");

    private TestOwners() {
    }
}
