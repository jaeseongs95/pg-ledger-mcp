package org.pgledger.database.schema;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guard of an application: the schema must be up to date (it only looks, never changes anything) and the JVM and
 * the database session must run in UTC. A failure stops the context from starting. It is made by
 * {@link LedgerSchemaGuardConfiguration}, so only the applications that import the guard have it (the command line tool
 * checks on demand, see its {@code SchemaReadiness}).
 */
@Component
public class SchemaGuard implements InitializingBean {

    private final SchemaVerifier verifier;
    private final UtcSessionCheck utcCheck;
    private final SchemaProperties properties;

    public SchemaGuard(SchemaVerifier verifier, UtcSessionCheck utcCheck, SchemaProperties properties) {
        this.verifier = verifier;
        this.utcCheck = utcCheck;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        verifier.verify();
        if (properties.requireUtc()) {
            utcCheck.verify();
        }
    }
}
