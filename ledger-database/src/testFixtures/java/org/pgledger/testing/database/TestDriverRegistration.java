package org.pgledger.testing.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.pgledger.domain.value.Sha256;
import org.pgledger.testing.OwnerOnlyFiles;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Registers the JDBC driver in a Spring test context the way a deployment has it: the settings {@code ledger.jdbc.driver.*}
 * name the PostgreSQL driver jar (a copy that only its owner can write, as {@code driver-register} leaves it: the product
 * refuses any other), its class and its digest. The product's own condition ({@code DriverRegisteredCondition}) then
 * opens the jar with the product's loader and holds, so the beans of the console and of the command line tool exist by the
 * production path and not by a profile. The context still brings its own {@code DataSource} ({@link
 * TestDataSourceConfiguration}); {@link TestDatabaseScanFilter} leaves the product's own out. Used as {@code initializers} of
 * {@code @SpringBootTest}, next to {@link TestSchemaInitializer}. The copy is made once per test JVM.
 */
public final class TestDriverRegistration implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** The registration as settings, for a runner or a launcher that has no initializer. */
    public static Map<String, Object> settings() {
        return Map.of("ledger.jdbc.driver.jar", Copy.JAR.toString().replace('\\', '/'),
                "ledger.jdbc.driver.class-name", PostgresDriver.CLASS_NAME, "ledger.jdbc.driver.sha256", Copy.SHA256);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-test-driver", settings()));
    }

    /** The one copy of the jar of this JVM. */
    private static final class Copy {

        static final Path JAR = OwnerOnlyFiles.copyToNewDirectory(PostgresDriver.jar());
        static final String SHA256 = digest(JAR);

        private static String digest(Path jar) {
            try {
                return Sha256.ofBytes(Files.readAllBytes(jar)).hex();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
