package org.pgledger.admin.setup;

import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What the driver page needs in both modes: the connection settings and the registration file. The file is the one bean
 * of the database module ({@link DriverRegistrationFile}, which owns the YAML mapper the command line tool uses for the same
 * file); only where the file is comes from this application's own setting.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
@Import(DriverRegistrationFile.class)
public class SetupConfiguration {

    /** A {@code @Bean} method because the value comes from a setting of this application ({@code ledger.admin.driver-file}). */
    @Bean
    DriverRegistrationFile.Location driverRegistrationFileLocation(LedgerAdminProperties properties) {
        return new DriverRegistrationFile.Location(properties.driverFile());
    }
}
