package org.pgledger.testing.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PostgreSQL of the test PC as a database for the tests (TEST_CASES.md 1.4a). H2 is not a test database.
 *
 * <ul>
 * <li>One small pool of the owner account per test JVM ({@link #pool()}, at most {@value #SHARED_POOL_SIZE}
 *     connections) is shared by every test and every cached Spring context, so that the connections of one test JVM stay
 *     within {@value #CONNECTION_BUDGET}: the shared pool, the pool of the application account ({@value #APP_POOL_SIZE}),
 *     and {@value #EXTRA_POOL_SIZE} for one pool of a test of its own (a session in another time zone).
 * <li>Every test class or context works in a schema of its own ({@link #newSchema()}, or a name reserved with
 *     {@link #reserveSchemaName()} that the product's migrator creates through {@code ledger.schema.name}). A schema is
 *     dropped when its test ends; whatever is left when the JVM ends is dropped by a shutdown hook, and the schemas of
 *     an interrupted earlier run are dropped when the database is first used ({@link #sweep}).
 * <li>The application account ({@link #appPool()}) is never replaced by the owner account: when it cannot log in the
 *     test that needs it fails with a message that names the property.
 * </ul>
 * A password is read from its file by {@link TestDatabaseSettings}; this class only hands it to the pools.
 */
public final class TestDatabase {

    /** Connections of the shared pool of the owner account. */
    public static final int SHARED_POOL_SIZE = 6;
    /** Connections of the pool of the application account. */
    public static final int APP_POOL_SIZE = 2;
    /** Connections left for one pool that a test opens itself ({@link #newPool}). */
    public static final int EXTRA_POOL_SIZE = 2;
    /** The most connections one test JVM may hold at the same time. */
    public static final int CONNECTION_BUDGET = 10;

    /** The name of a test schema: {@code t}, the UTC creation time to the second, {@code _}, eight hex digits. */
    private static final Pattern SCHEMA_NAME = Pattern.compile("t(\\d{14})_[0-9a-f]{8}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    /** A schema older than this belongs to a run that ended without dropping it. */
    private static final Duration LEFTOVER_AGE = Duration.ofHours(1);

    private static final Logger LOG = LoggerFactory.getLogger(TestDatabase.class);
    private static final TestDatabase SHARED = new TestDatabase(TestDatabaseSettings.fromSystemProperties());

    private final TestDatabaseSettings settings;
    private final AtomicInteger poolNumber = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private final Set<String> schemas = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private HikariDataSource pool;
    private HikariDataSource appPool;
    private boolean closed;
    private boolean hookInstalled;

    public TestDatabase(TestDatabaseSettings settings) {
        this.settings = settings;
    }

    /** The database of this test JVM, configured by the system properties of the build. */
    public static TestDatabase shared() {
        return SHARED;
    }

    public TestDatabaseSettings settings() {
        return settings;
    }

    // ---- pools --------------------------------------------------------------------------------------------------

    /** The shared pool of the owner account; sessions run in UTC. Do not close it. */
    public HikariDataSource pool() {
        synchronized (lock) {
            requireOpen();
            if (pool == null) {
                pool = open(settings.user(), settings.ownerPassword().orElse(null), SHARED_POOL_SIZE, "UTC",
                        TestDatabaseSettings.USER_PROPERTY);
                installHook();
                sweep(LEFTOVER_AGE);
            }
            return pool;
        }
    }

    /**
     * A pool of the owner account of its own whose sessions run in {@code zone} (an ANSI {@code SET TIME ZONE} per
     * connection); the caller closes it. At most {@value #EXTRA_POOL_SIZE} connections: the connection budget of the
     * test JVM has room for one such pool.
     */
    public HikariDataSource newPool(String zone) {
        pool();
        return open(settings.user(), settings.ownerPassword().orElse(null), EXTRA_POOL_SIZE, zone,
                TestDatabaseSettings.USER_PROPERTY);
    }

    /**
     * The shared pool of the application account (the least-privilege login). Do not close it.
     *
     * @throws IllegalStateException naming {@code ledger.test.app-user} when that account cannot log in; the owner
     *         account is never used instead
     */
    public HikariDataSource appPool() {
        synchronized (lock) {
            requireOpen();
            if (appPool == null) {
                appPool = open(settings.appUser(), settings.appPassword().orElse(null), APP_POOL_SIZE, "UTC",
                        TestDatabaseSettings.APP_USER_PROPERTY);
                installHook();
            }
            return appPool;
        }
    }

    /** A pool of the application account of its own whose sessions run in {@code zone}; the caller closes it. */
    public HikariDataSource newAppPool(String zone) {
        appPool();
        return open(settings.appUser(), settings.appPassword().orElse(null), EXTRA_POOL_SIZE, zone,
                TestDatabaseSettings.APP_USER_PROPERTY);
    }

    /** The pool configuration of an account (public for the test of the credentials; nothing is connected here). */
    public static HikariConfig config(TestDatabaseSettings settings, String account, String password, int size,
            String zone) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.url());
        config.setUsername(account);
        if (password != null) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
        config.setPoolName("ledger-test-" + account);
        return config;
    }

    private HikariDataSource open(String account, String password, int size, String zone, String property) {
        HikariConfig config = config(settings, account, password, size, zone);
        config.setPoolName(config.getPoolName() + "-" + poolNumber.incrementAndGet());
        try {
            return new HikariDataSource(config);
        } catch (PoolInitializationException e) {
            throw new IllegalStateException("the test database cannot be used: account '" + account + "' (test property "
                    + property + ") cannot connect to " + settings.url() + " (" + TestDatabaseSettings.URL_PROPERTY
                    + "): " + reasonOf(e), e);
        }
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String sqlState = cause instanceof SQLException sql && sql.getSQLState() != null ? " SQLState " + sql.getSQLState()
                : "";
        return cause.getClass().getSimpleName() + sqlState + ": " + cause.getMessage();
    }

    // ---- schemas ------------------------------------------------------------------------------------------------

    /** A schema name that no other test uses and the JVM will drop at its end (the schema itself is not created). */
    public String reserveSchemaName() {
        return reserveSchemaName(Instant.now());
    }

    /** As {@link #reserveSchemaName()} with the creation time {@code at} in the name (the test of the sweep). */
    public String reserveSchemaName(Instant at) {
        String name = "t" + STAMP.format(at) + "_" + HexFormat.of().formatHex(randomBytes());
        schemas.add(name);
        return name;
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return bytes;
    }

    /** A new, empty schema of its own; {@link TestSchema#close()} drops it. */
    public TestSchema newSchema() {
        return create(reserveSchemaName());
    }

    /** As {@link #newSchema()} with the creation time {@code at} in the name (the test of the sweep makes an old one). */
    public TestSchema newSchema(Instant at) {
        return create(reserveSchemaName(at));
    }

    /** Creates the schema {@code name} (reserved before) and returns its handle. */
    TestSchema create(String name) {
        execute("CREATE SCHEMA " + requireSchemaName(name));
        return new TestSchema(this, name);
    }

    /**
     * Drops the schema and everything in it; nothing happens when it does not exist. A failure is not swallowed.
     *
     * @throws IllegalStateException when the database refuses the drop
     */
    public void drop(String name) {
        requireSchemaName(name);
        if (exists(name)) {
            execute("DROP SCHEMA " + name + " CASCADE");
        }
        schemas.remove(name);
    }

    /** Whether the schema exists in the test database. */
    public boolean exists(String name) {
        try (Connection connection = pool().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet found = meta.getSchemas(null, name)) {
                while (found.next()) {
                    if (name.equals(found.getString("TABLE_SCHEM"))) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot look for the schema " + name + " in the test database", e);
        }
    }

    /** The names of the test schemas (the name pattern of this class) that exist in the test database. */
    public List<String> testSchemas() {
        List<String> found = new ArrayList<>();
        try (Connection connection = pool().getConnection(); ResultSet all = connection.getMetaData().getSchemas()) {
            while (all.next()) {
                String name = all.getString("TABLE_SCHEM");
                if (SCHEMA_NAME.matcher(name).matches()) {
                    found.add(name);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot list the schemas of the test database", e);
        }
        return found;
    }

    /**
     * Drops the test schemas that are older than {@code age}: the leftovers of a run that ended without dropping them.
     * A younger schema may belong to a test that is running and stays. Returns the dropped names.
     */
    public List<String> sweep(Duration age) {
        Instant limit = Instant.now().minus(age);
        List<String> dropped = new ArrayList<>();
        for (String name : testSchemas()) {
            Matcher stamp = SCHEMA_NAME.matcher(name);
            if (stamp.matches() && createdAt(stamp.group(1)).isBefore(limit)) {
                drop(name);
                dropped.add(name);
            }
        }
        if (!dropped.isEmpty()) {
            LOG.warn("dropped {} test schemas of an earlier run: {}", dropped.size(), dropped);
        }
        return dropped;
    }

    private static Instant createdAt(String digits) {
        try {
            return java.time.LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(
                    ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("not a test schema time: " + digits, e);
        }
    }

    private static String requireSchemaName(String name) {
        if (!SCHEMA_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not the name of a test schema: " + name);
        }
        return name;
    }

    /** Runs one statement with the owner account. */
    public void execute(String sql) {
        try (Connection connection = pool().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("the test database refused a statement of the test support: " + sql, e);
        }
    }

    /** Whether the pools are open (a context that closes at the end of the JVM does not drop through a closed pool). */
    public boolean isOpen() {
        synchronized (lock) {
            return !closed;
        }
    }

    // ---- end of the JVM -----------------------------------------------------------------------------------------

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("the test database support is already closed (the JVM is ending)");
        }
    }

    private void installHook() {
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutDown, "ledger-test-database-shutdown"));
        }
    }

    /** Drops the schemas that are still there, then closes the pools: one hook, so the order is fixed. */
    private void shutDown() {
        synchronized (lock) {
            if (pool != null) {
                for (String name : List.copyOf(schemas)) {
                    try {
                        drop(name);
                    } catch (IllegalStateException e) {
                        LOG.warn("the test schema {} could not be dropped at the end of the test JVM; the next run sweeps it",
                                name, e);
                    }
                }
                pool.close();
            }
            if (appPool != null) {
                appPool.close();
            }
            closed = true;
        }
    }

    /** The data source of a schema over {@code base}: every connection uses the schema (JDBC {@code setSchema}). */
    public static DataSource scoped(DataSource base, String schema) {
        return new ScopedDataSource(base, schema);
    }
}
