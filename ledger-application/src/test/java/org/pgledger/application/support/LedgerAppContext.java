package org.pgledger.application.support;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.pgledger.apptest.SigningAttestationVerifier;
import org.pgledger.apptest.TestKeyRing;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The view a test has of a Spring test context: the services and repositories as beans, the test clock, the SQL
 * recording of the data source, and a transaction of its own to run work in. The context itself comes from
 * Spring Boot's test support ({@link LedgerServiceTest}, or the runners of {@link LedgerTestRunners}); this
 * class assembles nothing. Every test works in its own project (see {@link Scenario}), so tests that share a
 * context do not collide.
 */
public final class LedgerAppContext {

    private final ApplicationContext context;

    public LedgerAppContext(ApplicationContext context) {
        this.context = context;
    }

    /** The data source of the context, which records every SQL text that passes through it. */
    public RecordingDataSource spy() {
        return (RecordingDataSource) context.getBean(DataSource.class);
    }

    public TestClock clock() {
        return context.getBean(TestClock.class);
    }

    /** The key pairs of the test authors and the signing of attestations (the hook of the service tests). */
    public TestKeyRing keys() {
        return context.getBean(TestKeyRing.class);
    }

    /** Registers the key of the author as the owner would, so the author can write (once per context). */
    public void enroll(String author) {
        context.getBean(SigningAttestationVerifier.class).enroll(author);
    }

    public JdbcClient jdbc() {
        return context.getBean(JdbcClient.class);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /** Runs the work in a transaction of its own that the services join. */
    public <T> T tx(Supplier<T> work) {
        return bean(TransactionTemplate.class).execute(status -> work.get());
    }

    /** Builds a fixture the first time it is asked for and returns the same one afterwards (for a whole test class). */
    public <T> T once(String key, Supplier<T> build) {
        return bean(LedgerTestFixtures.class).once(key, build);
    }

    /** A number no other scenario of this context has used. */
    int nextProjectNumber() {
        return bean(LedgerTestFixtures.class).nextProjectNumber();
    }
}
