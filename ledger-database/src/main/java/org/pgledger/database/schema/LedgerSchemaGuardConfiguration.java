package org.pgledger.database.schema;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Startup guard of an application: the schema services and the {@link SchemaGuard} that stops the context from starting
 * when the schema is not up to date or the JVM or the database session is not in UTC.
 */
@Configuration(proxyBeanMethods = false)
@Import({LedgerSchemaConfiguration.class, SchemaGuard.class})
public class LedgerSchemaGuardConfiguration {
}
