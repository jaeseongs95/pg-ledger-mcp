package org.pgledger.database.schema;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Startup guard of an application: the schema must be up to date (it only looks, never changes anything) and the
 * JVM and the database session must run in UTC. A failure stops the context from starting.
 */
@Configuration(proxyBeanMethods = false)
@Import(LedgerSchemaConfiguration.class)
public class LedgerSchemaGuardConfiguration {

    @Bean
    InitializingBean schemaGuard(SchemaVerifier verifier, UtcSessionCheck utcCheck, SchemaProperties properties) {
        return () -> {
            verifier.verify();
            if (properties.requireUtc()) {
                utcCheck.verify();
            }
        };
    }
}
