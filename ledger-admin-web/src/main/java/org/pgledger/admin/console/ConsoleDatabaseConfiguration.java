package org.pgledger.admin.console;

import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver and the startup guard that needs an
 * up-to-date schema and UTC.
 */
@Configuration(proxyBeanMethods = false)
@Import({LedgerDataSourceConfiguration.class, LedgerSchemaGuardConfiguration.class})
public class ConsoleDatabaseConfiguration {
}
