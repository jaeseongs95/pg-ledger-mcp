package org.pgledger.cli.database;

import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver (settings {@code ledger.jdbc.*}; the password
 * arrives through the secret store, a configtree property source) and the startup guard that needs an up-to-date schema
 * and UTC. The {@code embedded} profile leaves both out and lets the test bring its own DataSource. Like
 * {@link CliLedgerConfiguration} it exists only when {@link DriverRegisteredCondition} holds, so a machine without a
 * driver never tries to connect.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DriverRegisteredCondition.class)
@Profile("!embedded")
@Import({LedgerDataSourceConfiguration.class, LedgerSchemaGuardConfiguration.class})
public class CliDriverDataSourceConfiguration {
}
