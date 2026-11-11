package org.pgledger.database.driver;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's DataSource from the registered driver. Without a registration the context fails to start with
 * DRIVER_NOT_CONFIGURED; the bean order closes the pool before the driver's class loader.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LedgerJdbcProperties.class, SchemaProperties.class})
public class LedgerDataSourceConfiguration {

    @Bean(destroyMethod = "close")
    RegisteredDriver registeredDriver(LedgerJdbcProperties properties) {
        return DriverLoader.load(properties.driver());
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(RegisteredDriver registeredDriver, LedgerJdbcProperties properties,
            SchemaProperties schema) {
        HikariDataSource pool = DriverDataSources.create(registeredDriver, properties, schema.name());
        return pool;
    }
}
