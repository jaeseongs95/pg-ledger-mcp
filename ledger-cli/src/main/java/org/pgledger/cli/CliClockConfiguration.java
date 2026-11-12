package org.pgledger.cli;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of the tool, which every command may need whether or not a ledger database exists, so it has no condition. The
 * ledger database configuration of the tool ({@code CliLedgerConfiguration}) imports Spring Data JDBC without the clock
 * configuration of the database module, so there is exactly one {@link Clock}.
 */
@Configuration(proxyBeanMethods = false)
class CliClockConfiguration {

    /** A {@code @Bean} method because {@link Clock} is a JDK class: the one UTC clock of the tool (principle 4.5). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
