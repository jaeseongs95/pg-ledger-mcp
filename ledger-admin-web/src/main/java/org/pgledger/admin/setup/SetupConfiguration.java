package org.pgledger.admin.setup;

import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What the driver page needs in both modes: the connection settings, the registration file and the checks of a driver. The
 * file is the one bean of the database module ({@link DriverRegistrationFile}, which owns the YAML mapper the command line
 * tool uses for the same file) and the loader is the one that loads a driver class; only where the file is and which jars
 * are allowed come from this application's own settings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
@Import({DriverRegistrationFile.class, DriverLoader.class})
public class SetupConfiguration {

    /** A {@code @Bean} method because the value comes from a setting of this application ({@code ledger.admin.driver-file}). */
    @Bean
    DriverRegistrationFile.Location driverRegistrationFileLocation(LedgerAdminProperties properties) {
        return new DriverRegistrationFile.Location(properties.driverFile());
    }

    /**
     * A {@code @Bean} method because the checks take the JDBC URL and the one allowed directory of jars, which are values
     * of this application's settings ({@code ledger.jdbc.url}, {@code ledger.admin.driver-directory}), and the class
     * belongs to the database module, which the command line tool also uses with settings of its own; it is not a
     * component of either program.
     */
    @Bean
    DriverRegistrar driverRegistrar(DriverLoader loader, LedgerJdbcProperties jdbc, LedgerAdminProperties admin) {
        return new DriverRegistrar(loader, jdbc.url(), admin.driverDirectory());
    }
}
