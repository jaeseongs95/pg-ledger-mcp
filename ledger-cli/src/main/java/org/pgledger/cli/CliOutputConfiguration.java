package org.pgledger.cli;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only place that touches the process standard streams. */
@Configuration(proxyBeanMethods = false)
class CliOutputConfiguration {

    @Bean
    CliOutput cliOutput() {
        return new StreamCliOutput(System.out);
    }
}
