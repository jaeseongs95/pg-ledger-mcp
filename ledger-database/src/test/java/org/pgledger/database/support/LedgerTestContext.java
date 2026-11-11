package org.pgledger.database.support;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.value.ActorId;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One migrated database with the repositories of the ledger in a Spring context. The schema is built by the real
 * {@code db/V*.sql} files. {@link LedgerDatabaseExtension} shares one per DBMS and test run (tests use their own
 * project codes and ids, so they do not collide); the factories build separate ones.
 */
public final class LedgerTestContext implements AutoCloseable {

    /** The injected UTC clock starts here. */
    public static final Instant START = Instant.parse("2026-11-05T10:00:00Z");

    private final Dbms dbms;
    private final HikariDataSource pool;
    private final RecordingDataSource spy;
    private final TestClock clock = new TestClock(START);
    private final ConfigurableApplicationContext context;
    private final TransactionTemplate transactions;

    private LedgerTestContext(Dbms dbms, HikariDataSource pool, boolean migrate) {
        this.dbms = dbms;
        this.pool = pool;
        this.spy = new RecordingDataSource(pool);
        if (migrate) {
            new SchemaMigrator(spy, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(null, dbms.longText(), true, null), clock).migrate();
        }
        this.context = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN", "spring.jmx.enabled=false")
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("dataSource", spy);
                    ctx.getBeanFactory().registerSingleton("clock", clock);
                })
                .run();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(spy));
    }

    /** A context on a new database whose sessions run in UTC. */
    public static LedgerTestContext create(Dbms dbms) {
        return create(dbms, "UTC");
    }

    /** A context on a new database whose sessions run in {@code zone}. */
    public static LedgerTestContext create(Dbms dbms, String zone) {
        return new LedgerTestContext(dbms, dbms.newDataSource(zone, null), true);
    }

    /** A context on an already migrated database, reached through {@code pool} (for example a restricted account). */
    public static LedgerTestContext onMigrated(Dbms dbms, HikariDataSource pool) {
        return new LedgerTestContext(dbms, pool, false);
    }

    public Dbms dbms() {
        return dbms;
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
        pool.close();
    }
}
