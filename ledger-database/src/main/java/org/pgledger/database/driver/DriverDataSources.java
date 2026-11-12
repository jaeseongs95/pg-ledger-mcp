package org.pgledger.database.driver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.schema.ApplicationAccountAccess;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import java.util.List;

/**
 * Builds the pool from a registered driver: {@code Driver} -> {@link SimpleDriverDataSource} -> Hikari. The
 * {@code DriverManager} is not involved, so the driver of the registered jar is the one that connects.
 *
 * <p>Why the pool is assembled here and not by Spring Boot (principle 4.1, "the {@code DataSource} and the pool come from
 * Boot's auto-configuration"): Boot builds a Hikari pool from {@code spring.datasource.*}, which names a driver class that
 * Boot loads from the application class path. The driver of this product is a jar that the user registers at run time and
 * that lives in an isolated class loader ({@link DriverLoader}), so the {@link java.sql.Driver} instance is handed to
 * {@link SimpleDriverDataSource} (Spring's own {@code DataSource} for a given driver) and that one to Hikari, which Boot
 * would have used too. The pool settings are the ones of {@code ledger.jdbc.*}.
 */
public final class DriverDataSources {

    private DriverDataSources() {
    }

    /**
     * @param schema the schema every connection uses, or {@code null} for the default schema of the account; it must
     *               exist (the migrator creates it)
     */
    public static HikariDataSource create(RegisteredDriver registered, LedgerJdbcProperties properties,
            String schema) {
        // a registration written by hand cannot make the embedded database the ledger database either
        EmbeddedDatabaseRefusal.require(properties.url(), registered.driver().getClass().getName());
        registered.requireAccepts(properties.url());
        SimpleDriverDataSource simple = new SimpleDriverDataSource(registered.driver(), properties.url(),
                properties.username(), properties.password());
        HikariConfig config = new HikariConfig();
        config.setPoolName("ledger");
        config.setDataSource(simple);
        config.setMaximumPoolSize(properties.poolSize());
        if (schema != null) {
            config.setSchema(foldedSchema(simple, schema));
        }
        return new HikariDataSource(config);
    }

    /**
     * The check of the application account: a separate connection of that account (no pool, one connection per use) through the
     * registered driver, used by {@code setup} while the run itself is connected as another account (the owner account).
     *
     * @param application the settings of the application account ({@code ledger.jdbc.*} without the owner account of the run)
     */
    public static ApplicationAccountAccess applicationAccess(RegisteredDriver registered, LedgerJdbcProperties application,
            ResourcePatternResolver resolver, SchemaProperties schema) {
        EmbeddedDatabaseRefusal.require(application.url(), registered.driver().getClass().getName());
        registered.requireAccepts(application.url());
        return new ApplicationAccountAccess(application.username(),
                new SimpleDriverDataSource(registered.driver(), application.url(), application.username(),
                        application.password()),
                resolver, schema);
    }

    private static String foldedSchema(SimpleDriverDataSource simple, String schema) {
        try {
            return SchemaHistoryRepository.inspect(simple, metaData -> SchemaHistoryRepository.fold(metaData, schema));
        } catch (DataAccessException | InfrastructureException e) {
            // a refused connection (Spring DataAccessException) or an unreadable metadata (INTERNAL_ERROR)
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot connect with the registered driver: " + e.getMessage()), e);
        }
    }
}
