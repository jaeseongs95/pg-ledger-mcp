package org.pgledger.cli.secret;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Where secrets are stored; all values (and their defaults) come from application.properties and can be overridden. A
 * missing value stops the startup.
 *
 * @param instance  the instance name the default paths follow
 * @param home      the directory of the installation that holds the settings, the driver registration, the driver jars and
 *                  the secret directory (default {@code /etc/<instance>}; the Windows installation passes its own); it is
 *                  what the default paths and the {@code spring.config.import} of {@code application.properties} are made from
 * @param directory the secret directory (default {@code <home>/secrets})
 */
@Validated
@ConfigurationProperties("ledger.secrets")
public record SecretsProperties(@NotBlank String instance, @NotNull Path home, @NotNull Path directory) {

    @ConstructorBinding
    public SecretsProperties {
    }

    /** For code that has no installation of its own (the tests): the home is the parent of the secret directory. */
    public SecretsProperties(String instance, Path directory) {
        this(instance, directory.getParent(), directory);
    }
}
