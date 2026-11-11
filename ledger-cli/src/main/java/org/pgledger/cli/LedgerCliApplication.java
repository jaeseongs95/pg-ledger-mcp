package org.pgledger.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerCliApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(LedgerCliApplication.class, args)));
    }
}
