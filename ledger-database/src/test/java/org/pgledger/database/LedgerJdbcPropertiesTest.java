package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * DRV-046 (F6): the settings of the ledger database never print a password, neither the one of the settings nor the one
 * inside the JDBC URL. A record prints every component by default, and a settings object ends up in logs and failures.
 */
class LedgerJdbcPropertiesTest {

    private static final String SECRET = "s3cr3t-password-value";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerJdbcProperties.class)
    static class Settings {
    }

    @Test
    @DisplayName("DRV-046 toString shows the URL without its password, the account and the pool size, and never the password")
    void theTextOfTheSettingsHasNoPassword() {
        LedgerJdbcProperties settings = new LedgerJdbcProperties("jdbc:db://host/ledger?user=app&password=" + SECRET,
                "app", SECRET, 5, new DriverRegistration(null, "org.example.Driver", null, null));

        String text = settings.toString();

        assertThat(text).doesNotContain(SECRET).contains("username=app").contains("poolSize=5")
                .contains("org.example.Driver").contains("password=***");
    }

    @Test
    @DisplayName("DRV-046 settings bound from the environment print no password either, with the password in the settings and in the URL")
    void boundSettingsPrintNoPassword() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class)
                .withPropertyValues("ledger.jdbc.url=jdbc:db://app:" + SECRET + "@host/ledger?password=" + SECRET,
                        "ledger.jdbc.username=app", "ledger.jdbc.password=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LedgerJdbcProperties bound = context.getBean(LedgerJdbcProperties.class);
                    assertThat(bound.password()).as("the value is bound").isEqualTo(SECRET);
                    assertThat(bound.toString()).doesNotContain(SECRET).contains("poolSize=10");
                });
    }
}
