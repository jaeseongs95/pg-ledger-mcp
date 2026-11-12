package org.pgledger.database.driver;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * The connection pool of the legacy ledger, built from the registered driver exactly like the pool of the ledger database
 * ({@link DriverDataSources}: the {@code Driver} of the registered jar, never {@code DriverManager}). It is held in this
 * holder and not exposed as a {@link DataSource} bean, so the application has one {@code DataSource} bean, the ledger
 * database. Two connections: one for the read-only snapshot and one for the metadata of the legacy schema. Spring closes it
 * (it is {@link AutoCloseable}) before the driver's class loader, which it depends on.
 */
@Component
public final class LegacySourcePool implements AutoCloseable {

    private static final int CONNECTIONS = 2;

    private final HikariDataSource pool;

    LegacySourcePool(RegisteredDriver driver, LegacySourceProperties properties) {
        this.pool = DriverDataSources.create(driver,
                new LedgerJdbcProperties(properties.url(), properties.username(), properties.password(), CONNECTIONS,
                        null), null);
    }

    /** The pool, for the repository that reads the legacy schema. */
    public DataSource dataSource() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }
}
