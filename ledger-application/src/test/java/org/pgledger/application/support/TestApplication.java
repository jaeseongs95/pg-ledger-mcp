package org.pgledger.application.support;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** The smallest Spring Boot application around the ledger services (DataSource and Clock are supplied). */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(LedgerApplicationConfiguration.class)
public class TestApplication {
}
