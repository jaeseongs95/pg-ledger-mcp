package org.pgledger.application.support;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One migrated database with the ledger services in a Spring context. The schema is built by the real
 * {@code db/V*.sql} files. {@link LedgerAppExtension} shares one per DBMS and test run; every test works in its
 * own project (see {@link Scenario}), so tests do not collide.
 */
public final class LedgerAppContext implements AutoCloseable {

    /** The injected UTC clock starts here. */
    public static final Instant START = Instant.parse("2026-11-05T10:00:00Z");

    private final Dbms dbms;
    private final HikariDataSource pool;
    private final RecordingDataSource spy;
    private final TestClock clock = new TestClock(START);
    private final ConfigurableApplicationContext context;
    private final JdbcClient jdbc;
    private final AtomicInteger projects = new AtomicInteger();

    private LedgerAppContext(Dbms dbms, HikariDataSource pool, boolean seedCategories) {
        this.dbms = dbms;
        this.pool = pool;
        this.spy = new RecordingDataSource(pool);
        new SchemaMigrator(spy, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(null, dbms.longText(), true, null), clock).migrate();
        this.context = startApplication();
        this.jdbc = JdbcClient.create(pool);
        if (seedCategories) {
            CommonCodeService codes = bean(CommonCodeService.class);
            codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"));
            codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"));
        }
    }

    /**
     * Starts one more application (a restart of the server) on the same database and clock; the caller closes it.
     * The database stays open.
     */
    public ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN", "spring.jmx.enabled=false")
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("dataSource", spy);
                    ctx.getBeanFactory().registerSingleton("clock", clock);
                })
                .run();
    }

    public static LedgerAppContext create(Databases databases, Dbms dbms) {
        return new LedgerAppContext(dbms, databases.newDataSource(dbms, "UTC"), true);
    }

    /** A context whose database sessions run in {@code zone} (the guard is not part of this application). */
    public static LedgerAppContext createInZone(Databases databases, Dbms dbms, String zone) {
        return new LedgerAppContext(dbms, databases.newDataSource(dbms, zone), true);
    }

    /** A fresh installation: the category list is still empty. */
    public static LedgerAppContext createWithoutCategories(Databases databases, Dbms dbms) {
        return new LedgerAppContext(dbms, databases.newDataSource(dbms, "UTC"), false);
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

    public JdbcClient jdbc() {
        return jdbc;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /** Runs the work in a transaction of its own that the services join. */
    public <T> T tx(Supplier<T> work) {
        return bean(TransactionTemplate.class).execute(status -> work.get());
    }

    /** A number no other scenario of this database has used. */
    int nextProjectNumber() {
        return projects.incrementAndGet();
    }

    @Override
    public void close() {
        context.close();
        pool.close();
    }
}
