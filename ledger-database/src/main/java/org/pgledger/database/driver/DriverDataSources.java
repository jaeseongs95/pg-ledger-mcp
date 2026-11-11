package org.pgledger.database.driver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import java.util.List;

/**
 * Builds the pool from a registered driver: {@code Driver} -> {@link SimpleDriverDataSource} -> Hikari. The
 * {@code DriverManager} is not involved, so the driver of the registered jar is the one that connects.
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

    private static String foldedSchema(SimpleDriverDataSource simple, String schema) {
        try (Connection connection = simple.getConnection()) {
            return SchemaHistoryRepository.fold(connection.getMetaData(), schema);
        } catch (SQLException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot connect with the registered driver: " + e.getMessage()), e);
        }
    }
}
