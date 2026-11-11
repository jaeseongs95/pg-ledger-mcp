package org.pgledger.admin.setup;

import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * What the driver page needs in both modes. Spring Boot has no YAML mapper bean (the Jackson auto-configuration makes
 * the JSON one), so the one YAML mapper of the application, used only for the registration file, is made here
 * (principle 2.1: Jackson YAML is the standard YAML reader and writer of the Jackson family that Boot uses).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerJdbcProperties.class)
public class SetupConfiguration {

    @Bean
    YAMLMapper registrationYaml() {
        return YAMLMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
                .disable(tools.jackson.dataformat.yaml.YAMLWriteFeature.SPLIT_LINES)
                .build();
    }

    @Bean
    DriverRegistrationFile driverRegistrationFile(YAMLMapper registrationYaml, LedgerAdminProperties properties) {
        return new DriverRegistrationFile(registrationYaml, properties.driverFile());
    }
}
