package org.pgledger.cli.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The embedded H2 database in its default mode (no compatibility mode; the product ships no driver, the test classpath
 * has H2) with the schema built by the real {@code db/V*.sql} files and the real migrator, as the admin web tests build
 * it. The test reads the rows the CLI wrote through {@link #rows}.
 */
public final class TestLedgerDatabase implements AutoCloseable {

    /** The long text type of the schema files on H2, the only per-DBMS difference of the DDL. */
    private static final String LONG_TEXT = "CLOB";

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    private TestLedgerDatabase(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A new in-memory database with the whole schema migrated. */
    public static TestLedgerDatabase migrated() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000");
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        HikariDataSource pool = new HikariDataSource(config);
        new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, LONG_TEXT, true, null,
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), Clock.systemUTC()).migrate();
        return new TestLedgerDatabase(pool);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** The rows of an ANSI query (column names are case-insensitive keys). */
    public List<Map<String, Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
