package org.pgledger.mcp.config;

import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver (without a registration the context
 * does not start, see {@code DriverFailureAnalyzer}) and the startup guard that needs an up-to-date schema and UTC.
 * The {@code embedded} profile leaves both out and lets Spring Boot configure an embedded database from
 * {@code spring.datasource.*}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!embedded")
@Import({LedgerDataSourceConfiguration.class, LedgerSchemaGuardConfiguration.class})
public class ProductionDatabaseConfiguration {
}
