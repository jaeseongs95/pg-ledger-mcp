package org.pgledger.admin.console;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.database.driver.DriverRegisteredCondition;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The console: the ledger services and the pages that read and change the ledger. It exists only when
 * {@link DriverRegisteredCondition} holds; otherwise none of its beans (and no DataSource) is created.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DriverRegisteredCondition.class)
@Import(LedgerApplicationConfiguration.class)
@ComponentScan(basePackageClasses = ConsoleConfiguration.class)
public class ConsoleConfiguration {
}
