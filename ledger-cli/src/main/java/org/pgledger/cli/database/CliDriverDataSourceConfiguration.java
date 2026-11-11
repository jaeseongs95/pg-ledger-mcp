package org.pgledger.cli.database;

import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver (settings {@code ledger.jdbc.*}; the password
 * arrives through the secret store, a configtree property source). The {@code embedded} profile leaves it out and lets the
 * test bring its own DataSource. Like {@link CliLedgerConfiguration} it exists only when {@link DriverRegisteredCondition}
 * holds, so a machine without a driver never tries to connect. The schema guard of the server applications is not imported:
 * the CLI checks the schema per command ({@link SchemaReadiness}), because {@code migrate} must be able to start on an
 * empty database.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DriverRegisteredCondition.class)
@Profile("!embedded")
@Import(LedgerDataSourceConfiguration.class)
public class CliDriverDataSourceConfiguration {
}
