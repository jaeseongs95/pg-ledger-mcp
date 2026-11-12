package org.pgledger.cli.database;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.legacy.LegacyImportService;
import org.pgledger.cli.key.LedgerSessionKeyRegistry;
import org.pgledger.cli.key.SessionKeyRegistry;
import org.pgledger.database.LedgerDatabaseConfiguration;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.schema.UtcSessionCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The ledger services of the CLI and the doors of the commands to them. It exists only when
 * {@link CliDatabaseCondition} holds, exactly like the console of the admin web; otherwise none of its beans (and no
 * DataSource) is created and the commands that need the database find no {@link CliLedger}, no migrator and no
 * {@link SessionKeyRegistry}.
 *
 * <p>The services and the Spring Data JDBC setup come from {@link LedgerApplicationConfiguration}, the UTC {@code Clock}
 * from {@link LedgerDatabaseConfiguration}, the migrator and the schema checks from {@link LedgerSchemaConfiguration}; the
 * DataSource is in {@link CliDriverDataSourceConfiguration}. The schema is not checked at startup (the command
 * {@code migrate} must start on a database that is not up to date): every other command checks it first through
 * {@link SchemaReadiness}.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(CliDatabaseCondition.class)
@Import({LedgerApplicationConfiguration.class, LedgerDatabaseConfiguration.class, LedgerSchemaConfiguration.class})
public class CliLedgerConfiguration {

    @Bean
    SchemaReadiness schemaReadiness(SchemaVerifier verifier, UtcSessionCheck utcCheck, SchemaProperties properties) {
        return new SchemaReadiness(verifier, utcCheck, properties);
    }

    @Bean
    CliLedger cliLedger(ProjectService projects, CommonCodeService codes, GrantService grants,
            SessionKeyService sessionKeys, LegacyImportService importer, CliActor actor, SchemaReadiness readiness) {
        return new CliLedger(projects, codes, grants, sessionKeys, importer, actor, readiness);
    }

    @Bean
    SessionKeyRegistry sessionKeyRegistry(SessionKeyService sessionKeys, CliActor actor, SchemaReadiness readiness) {
        return new LedgerSessionKeyRegistry(sessionKeys, actor, readiness);
    }
}
