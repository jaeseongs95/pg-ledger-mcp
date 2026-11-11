package org.pgledger.application;

import org.pgledger.database.jdbc.LedgerJdbcConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What an application imports to use the ledger services: the services themselves and the Spring Data JDBC
 * setup of the database module. The DataSource, the UTC {@code Clock} and the transaction manager come from the
 * application (see {@code LedgerDatabaseConfiguration} and the data source configuration of the database module).
 */
@Configuration(proxyBeanMethods = false)
@Import(LedgerJdbcConfiguration.class)
@ComponentScan(basePackageClasses = LedgerApplicationConfiguration.class)
public class LedgerApplicationConfiguration {
}
