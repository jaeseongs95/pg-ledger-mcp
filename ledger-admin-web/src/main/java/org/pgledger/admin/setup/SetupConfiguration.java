package org.pgledger.admin.setup;

import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * What the driver page needs in both modes. Spring Boot has no YAML mapper bean (the Jackson auto-configuration makes
 * the JSON one), so the one YAML mapper of the application, used only for the registration file, is made here
 * (principle 2.1: Jackson YAML is the standard YAML reader and writer of the Jackson family that Boot uses); its
 * settings are those of {@link DriverRegistrationFile#newMapper()}, which the CLI uses for the same file.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
public class SetupConfiguration {

    @Bean
    YAMLMapper registrationYaml() {
        return DriverRegistrationFile.newMapper();
    }

    @Bean
    DriverRegistrationFile driverRegistrationFile(YAMLMapper registrationYaml, LedgerAdminProperties properties) {
        return new DriverRegistrationFile(registrationYaml, properties.driverFile());
    }
}
