package org.pgledger.cli.secret;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where secrets are stored: {@code ledger.secrets.directory}, by default {@code /etc/<instance>/secrets} with the
 * instance name {@code ledger.secrets.instance} (default pg-ledger-mcp).
 */
@ConfigurationProperties("ledger.secrets")
public record SecretsProperties(@DefaultValue("pg-ledger-mcp") String instance, Path directory) {

    public SecretsProperties {
        if (directory == null) {
            directory = Path.of("/etc", instance, "secrets");
        }
    }
}
