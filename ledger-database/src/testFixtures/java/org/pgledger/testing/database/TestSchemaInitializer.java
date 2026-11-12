package org.pgledger.testing.database;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;

/**
 * Gives a Spring test context a schema of its own: it takes a name from the test database support and sets it as the
 * product setting {@code ledger.schema.name}, which the migrator, the verifier and the UTC check read; the schema is
 * created by the product's migrator and dropped when the context closes (and, whatever happens, by the shutdown hook of
 * {@link TestDatabase}). Used as {@code initializers} of {@code @SpringBootTest} or {@code withInitializer} of an
 * {@code ApplicationContextRunner}. Spring's test context cache keys a context by this class, so the test classes that
 * name it share one context and one schema.
 */
public final class TestSchemaInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** The product setting that names the schema of the tables. */
    public static final String SCHEMA_PROPERTY = "ledger.schema.name";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestDatabase database = TestDatabase.shared();
        String name = database.reserveSchemaName();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-test-schema",
                Map.of(SCHEMA_PROPERTY, name)));
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent closed && closed.getApplicationContext() == context
                    && database.isOpen()) {
                database.drop(name);
            }
        });
    }
}
