package org.pgledger.cli.driver;

import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What {@code driver-register} needs whether or not a driver is registered: the connection settings (the JDBC URL the driver
 * has to accept) and the registration file, written with the YAML mapper of the database module (the admin web writes the
 * same file with the same mapper).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
class CliDriverConfiguration {

    @Bean
    DriverRegistrationFile driverRegistrationFile(DriverProperties properties) {
        return new DriverRegistrationFile(DriverRegistrationFile.newMapper(), properties.file());
    }
}
