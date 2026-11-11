package org.pgledger.database;

import java.time.Clock;
import org.pgledger.database.jdbc.LedgerJdbcConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What an application imports to use the ledger database: Spring Data JDBC with its converters and auditing, the
 * repositories, and the injected UTC clock, which is the only source of "now". The DataSource comes from
 * {@code LedgerDataSourceConfiguration} (the registered driver); the startup guard is
 * {@code LedgerSchemaGuardConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(LedgerJdbcConfiguration.class)
public class LedgerDatabaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
