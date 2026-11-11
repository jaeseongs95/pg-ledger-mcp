package org.pgledger.cli.database;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.cli.key.LedgerSessionKeyRegistry;
import org.pgledger.cli.key.SessionKeyRegistry;
import org.pgledger.cli.secret.ProcessUser;
import org.pgledger.database.LedgerDatabaseConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The ledger services of the CLI and the doors of the commands to them. It exists only when
 * {@link DriverRegisteredCondition} holds, exactly like the console of the admin web; otherwise none of its beans (and no
 * DataSource) is created and the commands that need the database find no {@link SessionKeyRegistry}.
 *
 * <p>The services and the Spring Data JDBC setup come from {@link LedgerApplicationConfiguration}, the UTC {@code Clock}
 * from {@link LedgerDatabaseConfiguration}; the DataSource and the startup guard are in
 * {@link CliDriverDataSourceConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DriverRegisteredCondition.class)
@Import({LedgerApplicationConfiguration.class, LedgerDatabaseConfiguration.class})
public class CliLedgerConfiguration {

    @Bean
    SessionKeyRegistry sessionKeyRegistry(SessionKeyService sessionKeys, ProcessUser processUser) {
        return new LedgerSessionKeyRegistry(sessionKeys, processUser);
    }
}
