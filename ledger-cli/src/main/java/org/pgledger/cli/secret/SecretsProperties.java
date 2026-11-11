package org.pgledger.cli.secret;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where secrets are stored; both values (and their defaults) come from application.properties and can be overridden. */
@ConfigurationProperties("ledger.secrets")
public record SecretsProperties(String instance, Path directory) {

    public SecretsProperties {
        Objects.requireNonNull(instance, "ledger.secrets.instance is required");
        Objects.requireNonNull(directory, "ledger.secrets.directory is required");
    }
}
