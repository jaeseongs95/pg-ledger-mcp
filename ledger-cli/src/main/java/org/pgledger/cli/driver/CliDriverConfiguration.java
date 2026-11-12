package org.pgledger.cli.driver;

import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What {@code driver-register} needs whether or not a driver is registered: the connection settings (the JDBC URL the driver
 * has to accept) and the registration file, which is the one bean of the database module ({@link DriverRegistrationFile},
 * with the YAML mapper that the admin web uses for the same file); only where the file is comes from the setting of this tool.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
@Import(DriverRegistrationFile.class)
class CliDriverConfiguration {

    /** A {@code @Bean} method because the value comes from a setting of this tool ({@code ledger.cli.driver.file}). */
    @Bean
    DriverRegistrationFile.Location driverRegistrationFileLocation(DriverProperties properties) {
        return new DriverRegistrationFile.Location(properties.file());
    }
}
