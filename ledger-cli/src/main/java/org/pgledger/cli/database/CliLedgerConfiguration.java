package org.pgledger.cli.database;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.cli.driver.PendingDriverAudit;
import org.pgledger.cli.key.LedgerSessionKeyRegistry;
import org.pgledger.cli.key.SessionKeyRegistry;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The ledger services of the CLI and the doors of the commands to them. It exists only when
 * {@link CliDatabaseCondition} holds, exactly like the console of the admin web; otherwise none of its beans (and no
 * DataSource) is created and the commands that need the database find no {@link CliLedger}, no migrator and no
 * {@link SessionKeyRegistry}.
 *
 * <p>The services and the Spring Data JDBC setup come from {@link LedgerApplicationConfiguration}, the migrator and the
 * schema checks from {@link LedgerSchemaConfiguration}; the DataSource is in
 * {@link CliDriverDataSourceConfiguration}. The UTC {@code Clock} is the one of the tool ({@code CliClockConfiguration}),
 * which has no condition, so this does not import the clock configuration of the database module. The schema is not checked
 * at startup (the command {@code migrate} must start on a database that is not up to date): every other command checks it
 * first through {@link SchemaReadiness}.
 *
 * <p>The classes of the tool that need the ledger services ({@code CliLedger}, the session key registry, the audit row of a
 * pending driver registration) are imported here as components, and are not annotated
 * with {@code @Component}: component scanning would make them without the condition, and without a database there is no
 * service to give them.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(CliDatabaseCondition.class)
@Import({LedgerApplicationConfiguration.class, LedgerSchemaConfiguration.class, SchemaReadiness.class, CliLedger.class,
        LedgerSessionKeyRegistry.class, PendingDriverAudit.class})
public class CliLedgerConfiguration {
}
