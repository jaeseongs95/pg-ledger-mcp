package org.pgledger.database.driver;

import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The application's DataSource from the registered driver. Without a registration the context fails to start with
 * DRIVER_NOT_CONFIGURED; the bean order closes the pool before the driver's class loader.
 *
 * <p>The two beans are {@code @Bean} methods, and not components, for one reason: neither can be made by the container
 * from its class. The driver is created at run time from a jar that the user registered ({@link DriverLoader}, in a class
 * loader of its own), and the pool is built around that driver instance, which Spring Boot's {@code DataSource}
 * auto-configuration cannot do (it loads a driver class by name from the application class path, see
 * {@link DriverDataSources}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LedgerJdbcProperties.class, SchemaProperties.class})
@Import(DriverLoader.class)
public class LedgerDataSourceConfiguration {

    /** The driver of the registered jar: a factory call of the loader (the instance comes out of a run-time class loader). */
    @Bean(destroyMethod = "close")
    RegisteredDriver registeredDriver(DriverLoader loader, LedgerJdbcProperties properties) {
        return loader.load(properties.driver());
    }

    /** A pool around the registered driver: an external type (Hikari) whose driver is not on the class path of Boot. */
    @Bean(destroyMethod = "close")
    DataSource dataSource(RegisteredDriver registeredDriver, LedgerJdbcProperties properties,
            SchemaProperties schema) {
        return DriverDataSources.create(registeredDriver, properties, schema.name());
    }
}
