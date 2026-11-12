package org.pgledger.cli.database;

import org.pgledger.cli.owner.OwnerAccountPropertySource;
import org.pgledger.database.driver.DriverDataSources;
import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.database.driver.LegacySourceConfiguration;
import org.pgledger.database.driver.RegisteredDriver;
import org.pgledger.database.schema.ApplicationAccountAccess;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver (settings {@code ledger.jdbc.*}; the password
 * arrives through the secret store, a configtree property source). The {@code embedded} profile leaves it out and lets the test
 * bring its own DataSource. Like {@link CliLedgerConfiguration} it exists only when {@link CliDatabaseCondition}
 * holds, so a machine without a driver never tries to connect. The schema guard of the server applications is not imported:
 * the CLI checks the schema per command ({@link SchemaReadiness}), because {@code migrate} must be able to start on an
 * empty database.
 *
 * <p>The application account: a run of {@code migrate} or {@code setup} connects as the owner account (the DDL account,
 * {@link OwnerAccountPropertySource}), which shadows {@code ledger.jdbc.username} and {@code ledger.jdbc.password}. The check
 * of {@code setup} whether the application account itself can use the schema needs the settings of that account, so they are
 * bound from the property sources without the owner account (the same settings the servers connect with).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(CliDatabaseCondition.class)
@Profile("!embedded")
@Import({LedgerDataSourceConfiguration.class, LegacySourceConfiguration.class})
public class CliDriverDataSourceConfiguration {

    /** A {@code @Bean} method because the settings are bound from a filtered view of the environment, which no annotation can say. */
    @Bean
    ApplicationAccountAccess applicationAccountAccess(RegisteredDriver driver, ConfigurableEnvironment environment,
            ResourcePatternResolver resolver, SchemaProperties schema) {
        MutablePropertySources sources = new MutablePropertySources(environment.getPropertySources());
        sources.remove(OwnerAccountPropertySource.NAME);
        // the attached view of the environment would show the owner account again
        sources.remove(ConfigurationPropertySources.ATTACHED_PROPERTY_SOURCE_NAME);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("ledger.jdbc", LedgerJdbcProperties.class)
                .map(application -> DriverDataSources.applicationAccess(driver, application, resolver, schema))
                .orElseGet(() -> ApplicationAccountAccess.notConfigured(resolver, schema));
    }
}
