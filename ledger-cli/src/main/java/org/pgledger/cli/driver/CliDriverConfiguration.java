package org.pgledger.cli.driver;

import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What {@code driver-register} needs whether or not a driver is registered: the connection settings (the JDBC URL the driver
 * has to accept), the loader that checks a jar, the registration file, which is the one bean of the database module
 * ({@link DriverRegistrationFile}, with the YAML mapper that the admin web uses for the same file), and the registrar that
 * checks a jar and a class. Only where the file is and which directory a jar comes from are settings of this tool.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
@Import({DriverRegistrationFile.class, DriverLoader.class})
class CliDriverConfiguration {

    /** A {@code @Bean} method because the value comes from a setting of this tool ({@code ledger.cli.driver.file}). */
    @Bean
    DriverRegistrationFile.Location driverRegistrationFileLocation(DriverProperties properties) {
        return new DriverRegistrationFile.Location(properties.file());
    }

    /**
     * A {@code @Bean} method because the registrar is a class of the database module that serves two programs (this tool and
     * the admin web) and takes what differs between them as arguments: the JDBC URL the driver has to accept
     * ({@code ledger.jdbc.url}) and the one directory a jar may be in ({@code ledger.cli.driver.directory}, a setting of this
     * tool). The module cannot make it a component, because it does not know either setting of the program.
     */
    @Bean
    DriverRegistrar driverRegistrar(DriverLoader loader, LedgerJdbcProperties jdbc, DriverProperties properties) {
        return new DriverRegistrar(loader, jdbc.url(), properties.directory());
    }
}
