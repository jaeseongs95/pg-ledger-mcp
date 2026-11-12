package org.pgledger.database.support;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.value.ActorId;
import org.pgledger.testing.database.TestDatabase;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One migrated schema of the test database with the repositories of the ledger in a Spring context. The schema is
 * built by the real {@code db/V*.sql} files and named by the product setting {@code ledger.schema.name}.
 * {@link LedgerDatabaseExtension} shares one per test run (tests use their own project codes and ids, so they do not
 * collide); the factories build separate ones. A schema that a factory made is dropped when the context is closed; the
 * schema of the caller of {@link #onMigrated} stays the caller's.
 */
public final class LedgerTestContext implements AutoCloseable {

    /** The injected UTC clock starts here. */
    public static final Instant START = Instant.parse("2026-11-05T10:00:00Z");

    private final DataSource pool;
    private final HikariDataSource owned;
    /** The schema this context made and drops at its end; null for a schema of the caller. */
    private final String schema;
    private final RecordingDataSource spy;
    private final TestClock clock = new TestClock(START);
    private final ConfigurableApplicationContext context;
    private final TransactionTemplate transactions;

    private LedgerTestContext(DataSource pool, HikariDataSource owned, String schema, boolean migrate) {
        this.schema = migrate ? schema : null;
        this.pool = pool;
        this.owned = owned;
        this.spy = new RecordingDataSource(pool);
        if (migrate) {
            new SchemaMigrator(spy, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, schema,
                            java.time.Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate();
        }
        this.context = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN", "spring.jmx.enabled=false",
                        "ledger.schema.name=" + schema)
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("dataSource", spy);
                    ctx.getBeanFactory().registerSingleton("clock", clock);
                })
                .run();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(spy));
    }

    /** A context on a new schema whose sessions run in UTC. */
    public static LedgerTestContext create(Databases databases) {
        return create(databases, "UTC");
    }

    /** A context on a new schema whose sessions run in {@code zone} (a pool of its own unless the zone is UTC). */
    public static LedgerTestContext create(Databases databases, String zone) {
        TestDatabase database = databases.database();
        String schema = database.reserveSchemaName();
        if ("UTC".equals(zone)) {
            return new LedgerTestContext(TestDatabase.scoped(database.pool(), schema), null, schema, true);
        }
        HikariDataSource own = databases.newPool(zone);
        return new LedgerTestContext(TestDatabase.scoped(own, schema), own, schema, true);
    }

    /**
     * A context on an already migrated schema, reached through {@code pool} (for example the restricted application
     * account); {@code schema} is the name that {@code ledger.schema.name} gets.
     */
    public static LedgerTestContext onMigrated(DataSource pool, String schema) {
        return new LedgerTestContext(pool, null, schema, false);
    }

    public DataSource pool() {
        return pool;
    }

    public RecordingDataSource spy() {
        return spy;
    }

    public TestClock clock() {
        return clock;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /** Runs {@code work} inside a transaction managed here (the repositories join it). */
    public <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /** Runs {@code work} with {@code actor} bound for auditing. */
    public static <T> T as(ActorId actor, Supplier<T> work) {
        return ScopedValue.where(ActorScope.CURRENT, actor).call(work::get);
    }

    @Override
    public void close() {
        context.close();
        if (schema != null) {
            TestDatabase.shared().drop(schema);
        }
        if (owned != null) {
            owned.close();
        }
    }
}
