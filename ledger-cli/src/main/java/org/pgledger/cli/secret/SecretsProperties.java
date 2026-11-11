package org.pgledger.cli.secret;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where secrets are stored; both values (and their defaults) come from application.properties and can be overridden.
 * A missing value stops the startup.
 */
@Validated
@ConfigurationProperties("ledger.secrets")
public record SecretsProperties(@NotBlank String instance, @NotNull Path directory) {
}
