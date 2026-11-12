package org.pgledger.database.schema;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * The schema services: the migrator and the grant applier (for the operator's CLI), the verifier and the UTC check (for
 * the applications). They are components of this package, found by the scan below. Nothing runs at startup here: the
 * startup guard ({@link SchemaGuard}) is left out of the scan on purpose, and is added by
 * {@link LedgerSchemaGuardConfiguration} for the applications that want it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaProperties.class)
@ComponentScan(basePackageClasses = SchemaMigrator.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = {SchemaGuard.class, LedgerSchemaGuardConfiguration.class}))
public class LedgerSchemaConfiguration {
}
