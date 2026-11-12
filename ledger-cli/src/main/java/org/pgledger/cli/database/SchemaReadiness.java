package org.pgledger.cli.database;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.schema.UtcSessionCheck;

/**
 * The startup guard of the server applications, run by the command instead of at startup: the schema must be up to date
 * ({@link SchemaVerifier}, it only looks) and the JVM and the database session must run in UTC ({@link UtcSessionCheck}).
 * It cannot run at startup, because the command {@code migrate} has to start on a database that is not up to date. A
 * failure is the CLI refusal SCHEMA_NOT_READY (the reason names files and versions, never a secret). It exists only when
 * the CLI has its database.
 */
public final class SchemaReadiness {

    private final SchemaVerifier verifier;
    private final UtcSessionCheck utcCheck;
    private final SchemaProperties properties;
    private boolean verified;

    public SchemaReadiness(SchemaVerifier verifier, UtcSessionCheck utcCheck, SchemaProperties properties) {
        this.verifier = verifier;
        this.utcCheck = utcCheck;
        this.properties = properties;
    }

    /** Checks once per process; a failed check is repeated by the next call. */
    public synchronized void require() {
        if (verified) {
            return;
        }
        try {
            verifier.verify();
            if (properties.requireUtc()) {
                utcCheck.verify();
            }
        } catch (InfrastructureException notReady) {
            throw new FailureException(ErrorCode.SCHEMA_NOT_READY, notReady, notReady.getMessage());
        }
        verified = true;
    }
}
