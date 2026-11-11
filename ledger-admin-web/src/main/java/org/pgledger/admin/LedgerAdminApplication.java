package org.pgledger.admin;

import org.pgledger.admin.console.ConsoleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The admin web of the ledger: server-rendered pages (Spring MVC and Thymeleaf) over the application services, behind
 * one Spring Security chain. The pages that need the ledger database live in the {@code console} package, which is not
 * scanned here: {@link ConsoleConfiguration} brings it in only when a usable JDBC driver is registered. Without one
 * the application starts in setup mode (login and driver registration only).
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} is excluded because the DataSource of the product comes from the
 * registered driver (console mode) and must not be guessed in setup mode.
 */
@SpringBootApplication(scanBasePackages = {"org.pgledger.admin.app", "org.pgledger.admin.security",
    "org.pgledger.admin.web", "org.pgledger.admin.setup"}, exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan("org.pgledger.admin")
@Import(ConsoleConfiguration.class)
public class LedgerAdminApplication {

    public static void main(String[] arguments) {
        SpringApplication.run(LedgerAdminApplication.class, arguments);
    }
}
